package com.johnowl.gateway.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.session.MapSession;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionIdGenerator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The session store {@code memory} (spec 022, "Session store"): the sessions of one gateway
 * instance in a map, like the {@code ReactiveMapSessionRepository} of Spring Session, with a limit.
 *
 * <ul>
 *   <li>A new session gets the pre-login timeout and an id from the {@link SessionIdGenerator}.
 *   <li>The store holds at most {@code maxSessions} sessions. For a new session in a full store, it
 *       removes the oldest pre-login session first. A pre-login session has no security context.
 *       If all sessions have an authenticated user, the store refuses the new session with a
 *       {@link SessionStoreFullException}.
 *   <li>A task on its own thread removes the expired sessions every minute. It never runs on the
 *       event loop.
 *   <li>The token state of spec 023 (the attributes {@code gateway.oauth2.*}) belongs to the store. Only
 *       {@link #updateAttribute} changes it, atomically. A save of a request copy keeps the stored
 *       token state, so a request that loaded an older copy cannot replace a newer refresh token.
 *   <li>After {@link #deleteById}, a save of a request copy of that session does not create the
 *       session again (spec 023, "Storage of authorized clients"). The store remembers the ids of deleted
 *       sessions for 10 minutes, and at most {@code 2 * maxSessions} of them. A deletion of an id that the
 *       store does not hold, for example an anonymous {@code POST /logout}, remembers nothing.
 * </ul>
 *
 * <p>All operations work on concurrent collections and never wait for a lock. With parallel
 * requests, the store can hold a few sessions more than the limit for a short time.
 */
final class MemorySessionRepository implements ReactiveSessionRepository<MapSession>, SmartLifecycle {

    static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);

    /** How long the store remembers a deleted session id. Longer than any request. */
    static final Duration DELETED_LIFETIME = Duration.ofMinutes(10);

    /** The prefix of the token state attributes of spec 023. */
    static final String TOKEN_STATE_PREFIX = "gateway.oauth2.";

    private static final String SECURITY_CONTEXT_ATTRIBUTE =
            WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME;

    private static final Logger log = LoggerFactory.getLogger(MemorySessionRepository.class);

    /** The stored sessions. A stored {@link Session} is never changed; a change replaces it with a new copy. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** The ids of deleted sessions. Session ids are never reused. Bounded in time and in size. */
    private final Cache<String, Boolean> deleted;

    /** The ids of the pre-login sessions, oldest first. Ids of removed or authenticated sessions stay until the next cleanup. */
    private final Queue<String> preLoginIds = new ConcurrentLinkedQueue<>();

    private final SessionIdGenerator idGenerator;

    private final Duration preLoginTimeout;

    private final int maxSessions;

    /** One log line for each cleanup interval, also when an attack fills the store. */
    private final AtomicBoolean fullLogged = new AtomicBoolean();

    private volatile Scheduler cleanupScheduler;

    private volatile Disposable cleanup;

    MemorySessionRepository(SessionIdGenerator idGenerator, Duration preLoginTimeout, int maxSessions) {
        this(idGenerator, preLoginTimeout, maxSessions, Ticker.systemTicker());
    }

    /** Tests give a ticker to pass the lifetime of a deleted id without waiting. */
    MemorySessionRepository(SessionIdGenerator idGenerator, Duration preLoginTimeout, int maxSessions, Ticker ticker) {
        if (maxSessions < 1) {
            throw new IllegalStateException("gateway.session.memory.max-sessions must be at least 1, but is "
                    + maxSessions + ".");
        }
        this.idGenerator = idGenerator;
        this.preLoginTimeout = preLoginTimeout;
        this.maxSessions = maxSessions;
        // The calling thread does the maintenance: it is short and never waits.
        this.deleted = Caffeine.newBuilder()
                .expireAfterWrite(DELETED_LIFETIME)
                .maximumSize(2L * maxSessions)
                .executor(Runnable::run)
                .ticker(ticker)
                .build();
    }

    @Override
    public Mono<MapSession> createSession() {
        return Mono.fromSupplier(() -> {
            MapSession session = new MapSession(idGenerator);
            session.setMaxInactiveInterval(preLoginTimeout);
            return session;
        });
    }

    @Override
    public Mono<Void> save(MapSession session) {
        return Mono.fromRunnable(() -> store(session));
    }

    @Override
    public Mono<MapSession> findById(String id) {
        return Mono.fromSupplier(() -> {
            Session stored = sessions.get(id);
            if (stored == null) {
                return null;
            }
            if (stored.isExpired()) {
                sessions.remove(id, stored);
                return null;
            }
            MapSession copy = new MapSession(stored);
            copy.setSessionIdGenerator(idGenerator);
            return copy;
        });
    }

    @Override
    public Mono<Void> deleteById(String id) {
        // The mark and the removal are one atomic step for the id: a save of the same id runs before
        // both or sees the mark. An id that the store does not hold gets no mark.
        return Mono.fromRunnable(() -> sessions.computeIfPresent(id, (key, stored) -> {
            deleted.put(key, Boolean.TRUE);
            return null;
        }));
    }

    /** The value of an attribute of the stored session, not of a request copy. {@code null} without a session. */
    Object attribute(String id, String name) {
        Session stored = sessions.get(id);
        return stored == null || stored.isExpired() ? null : stored.getAttribute(name);
    }

    /**
     * Changes one attribute of the stored session atomically. The update gets the stored value and
     * returns the new value; {@code null} removes the attribute.
     *
     * @return {@code false} if the store has no such session; the update did not run
     */
    boolean updateAttribute(String id, String name, UnaryOperator<Object> update) {
        boolean[] found = new boolean[1];
        sessions.computeIfPresent(id, (key, stored) -> {
            if (stored.isExpired()) {
                return stored;
            }
            found[0] = true;
            Object current = stored.getAttribute(name);
            Object value = update.apply(current);
            if (value == current) {
                return stored;
            }
            MapSession changed = new MapSession(stored);
            if (value == null) {
                changed.removeAttribute(name);
            } else {
                changed.setAttribute(name, value);
            }
            return changed;
        });
        return found[0];
    }

    /** {@code true} if the store holds a session with this id, also an expired one. For the tests. */
    boolean contains(String id) {
        return sessions.containsKey(id);
    }

    /** The number of sessions in the store, also the expired ones. */
    int size() {
        return sessions.size();
    }

    /** The number of remembered ids of deleted sessions. For the tests. */
    long deletedIds() {
        deleted.cleanUp();
        return deleted.estimatedSize();
    }

    /** Removes the expired sessions, and the ids of sessions that are no longer pre-login sessions. */
    void removeExpired() {
        sessions.values().removeIf(Session::isExpired);
        deleted.cleanUp();
        preLoginIds.removeIf(id -> !isPreLogin(sessions.get(id)));
        fullLogged.set(false);
    }

    private void store(MapSession session) {
        String id = session.getId();
        String originalId = session.getOriginalId();
        if (isDeleted(originalId)) {
            return;   // another request deleted the session: never write it back
        }
        boolean known = sessions.containsKey(originalId) || sessions.containsKey(id);
        if (!known) {
            makeRoom();
        }
        if (!id.equals(originalId)) {
            // A new session id, for example at login: the session keeps its place and its token state.
            Session previous = sessions.remove(originalId);
            sessions.put(id, withStoredTokenState(session, previous));
        } else {
            sessions.compute(id, (key, stored) -> isDeleted(key) ? null : withStoredTokenState(session, stored));
        }
        if (!known && isPreLogin(session)) {
            preLoginIds.add(id);
        }
    }

    private boolean isDeleted(String id) {
        return deleted.getIfPresent(id) != null;
    }

    /** A copy of the saved session, with the token state of the stored session if the store has one. */
    private static MapSession withStoredTokenState(MapSession saved, Session stored) {
        MapSession copy = new MapSession(saved);
        if (stored != null) {
            for (String name : saved.getAttributeNames()) {
                if (name.startsWith(TOKEN_STATE_PREFIX)) {
                    copy.removeAttribute(name);
                }
            }
            for (String name : stored.getAttributeNames()) {
                if (name.startsWith(TOKEN_STATE_PREFIX)) {
                    copy.setAttribute(name, stored.getAttribute(name));
                }
            }
        }
        return copy;
    }

    /** Removes pre-login sessions, oldest first, until there is room for one session. */
    private void makeRoom() {
        while (sessions.size() >= maxSessions) {
            String oldest = preLoginIds.poll();
            if (oldest == null) {
                if (fullLogged.compareAndSet(false, true)) {
                    log.warn("The in-memory session store holds {} authenticated sessions "
                            + "(gateway.session.memory.max-sessions). It refuses new sessions.", maxSessions);
                }
                throw new SessionStoreFullException();
            }
            // Atomic: a session that became authenticated in the meantime stays.
            sessions.computeIfPresent(oldest, (key, stored) -> isPreLogin(stored) ? null : stored);
        }
    }

    private static boolean isPreLogin(Session session) {
        return session != null && session.getAttribute(SECURITY_CONTEXT_ATTRIBUTE) == null;
    }

    @Override
    public void start() {
        cleanupScheduler = Schedulers.newSingle("gateway-session-cleanup", true);
        cleanup = Flux.interval(CLEANUP_INTERVAL, CLEANUP_INTERVAL, cleanupScheduler)
                .subscribe(tick -> removeExpired());
    }

    @Override
    public void stop() {
        Disposable running = cleanup;
        if (running != null) {
            running.dispose();
            cleanupScheduler.dispose();
            cleanup = null;
        }
    }

    @Override
    public boolean isRunning() {
        return cleanup != null;
    }
}
