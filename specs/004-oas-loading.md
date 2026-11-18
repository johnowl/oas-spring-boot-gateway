# 004 — OAS loading

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 2 — OAS core |
| Depends on | [001](001-project-skeleton.md) |
| Modules | `openapi-gateway-core` |

## Summary

This spec adds the code that finds and parses OpenAPI Specification (OAS) files in `openapi-gateway-core`. `SpecFinder` finds OAS files in files and directories. `SpecLoader` parses each file with swagger-parser, resolves references, and keeps the raw JSON tree. The result is a `SpecFile`. A file that cannot be parsed produces violations with the rule id `oas-parse`, not an exception. The CLI and the gateway use the same code, so the same file gives the same result in both.

## Motivation

The validation engine ([005](005-validation-engine.md)) and the route model ([007](007-route-model.md)) need a parsed model of each OAS file. Some rules also need the raw document. For example, the `x-gateway` JSON Schema validation in [006](006-x-gateway-extension.md) works on a JSON tree, and the CLI maps JSON pointers to line numbers in [009](009-cli-github-and-json-output.md). If the CLI and the gateway parsed files in different ways, a file could pass the CLI and break the gateway.

## Scope

### In scope

- `SpecFinder`: finds `*.yaml`, `*.yml`, and `*.json` files.
- `SpecLoader`: parses with swagger-parser, resolves `$ref`, supports OAS 3.0 and OAS 3.1.
- `SpecFile(path, OpenAPI, raw JsonNode)`.
- `oas-parse` violations for files that cannot be parsed.

### Out of scope

- The validation engine and the `Violation` type in its final form. See [005](005-validation-engine.md).
- Mapping JSON pointers to line and column. See [009](009-cli-github-and-json-output.md).
- Loading from the Spring `classpath:` location. The gateway does this in [013](013-oas-route-locator.md) and passes the content to `SpecLoader`.
- Swagger 2.0 files. They produce an `oas-parse` violation.

## Design

### Package and classes

Package: `com.johnowl.gateway.core.spec`.

| Type | Responsibility |
|---|---|
| `SpecFinder` | Expands a list of files and directories into a sorted list of OAS file paths. |
| `SpecSource` | `record SpecSource(String path, String content)`. Input of the loader. Lets the gateway pass content from a classpath resource. |
| `SpecLoader` | Parses one `SpecSource` into a `SpecLoadResult`. |
| `SpecLoadResult` | `record SpecLoadResult(Optional<SpecFile> spec, List<Violation> violations)`. |
| `SpecFile` | `record SpecFile(String path, OpenAPI openApi, JsonNode raw)`. |

`path` is the location that violations show, for example `gateway/src/main/resources/openapi/orders.yaml`. The CLI uses the path relative to the working directory. The gateway uses the resource location.

### `SpecFinder`

```java
public final class SpecFinder {
    public List<Path> find(List<Path> filesOrDirectories) throws IOException;
}
```

Rules:

1. A file argument is returned as it is, with any extension.
2. A directory argument is searched recursively for files with the extensions `.yaml`, `.yml`, and `.json`. The search skips symbolic links to files and does not follow symbolic links to directories. A link in a reviewed directory therefore cannot make the CLI read a file outside it. A file argument that is a symbolic link is read.
3. The result contains each file once and is sorted by the normalized path. The order is therefore the same on every machine. Two arguments that name the same file in different ways (a relative and an absolute path, or a symbolic link) give one entry: the first one in sorted order.
4. An argument that does not exist causes an `IOException`. The CLI maps it to exit code 2 ([008](008-cli-basic.md)).
5. A directory that cannot be read (for example, no read permission) also causes an `IOException`, not an unchecked exception.

### `SpecLoader`

```java
public final class SpecLoader {
    public SpecLoadResult load(Path file) throws IOException;   // reads the file as UTF-8
    public SpecLoadResult load(SpecSource source);
}
```

The loader does these steps for each file:

1. Check the size. A file larger than 5 MB (5,242,880 bytes) is rejected before it is read. YAML content larger than 3 MB (3,145,728 characters) is rejected before it is parsed (see "Size limits").
2. Parse the content with Jackson (`jackson-dataformat-yaml` for YAML, `jackson-databind` for JSON) into the raw `JsonNode`. The content is JSON if the file name ends with `.json` or the content starts with `{` (see "One document"). JSON content can start with one byte order mark (`U+FEFF`).
3. Reject content after the first document and keys longer than 1024 characters. For YAML, also reject anchors, aliases, merge keys, and explicit tags (see "Safe loading").
4. Check the `openapi` field. The value MUST start with `3.0.` or `3.1.`.
5. Check every reference, and reject every path item `$ref`, in the root file and in each referenced file (see "Safe loading").
6. Parse the content with swagger-parser (`OpenAPIV3Parser`) and `ParseOptions.setResolve(true)`.
7. Convert each swagger-parser message into one `oas-parse` violation.
8. Check that the model has the same paths and HTTP methods as the raw tree (see "Safe loading").
9. Return a `SpecFile` only if steps 1–8 produce no violation.

Relative external references (for example `$ref: ./common.yaml#/components/schemas/Error`) are resolved relative to the directory of the file that contains them.

swagger-parser copies a schema of another file into `components/schemas` and changes the references to it to `#/components/schemas/<name>`. It does not change every reference. In OAS 3.0, a schema in `schemas.yaml` with `$ref: "#/Order"` (a reference to `Order` in the same file) keeps the value `#/Order` in the model, also when the copy of `Order` is in `components/schemas`. A rule that resolves references in the model therefore cannot resolve every reference. [031](031-documentation-rules.md) describes how `schema-examples-present` handles this (finding QA-66-1).

### Safe loading

The gateway loads the same files at startup, in production. The loader therefore never accesses the network and never reads a file outside the directory of the root OAS file.

**References.** A reference is the value of a `$ref` field or of a `discriminator.mapping` entry. The loader checks every reference in the raw tree before swagger-parser runs. It also reads each referenced file and checks the references in that file, recursively.

| Reference | `load(Path)` | `load(SpecSource)` |
|---|---|---|
| Local: `#/...`, or a component name without `.`, `/`, `\`, `:`, and `%` (for example `Pet`) | allowed | allowed |
| Relative file path inside the directory of the root file, for example `./common.yaml#/X` or `schemas/a.yaml` | allowed | rejected |
| URL or any other scheme, for example `http://...`, `https://...`, `file:...` | rejected | rejected |
| Absolute path, or a path with `\` or `%` | rejected | rejected |
| Relative path that leaves the directory of the root file, for example `../x.yaml`, also through a symbolic link | rejected | rejected |

`load(SpecSource)` has no directory (the gateway passes classpath content), so it allows only local references. A rejected reference is an `oas-parse` violation at the pointer of the `$ref`. If the reference is in a referenced file, the pointer is the pointer of the `$ref` in the root file that leads to that file, and the message names the referenced file.

**Path item references.** A Path Item Object MUST NOT have a `$ref`. The operations of each path item must be inline. The loader rejects every path item `$ref`, local or to a file, with an `oas-parse` violation at the pointer of the path item (review findings SEC-66-1 and SEC-67-1). The loader does not resolve the reference itself.

swagger-parser 2.1.48 reads path item references in a way that the rules and the gateway cannot trust:

- It does not resolve a `$ref` to `#/components/pathItems/<name>` (OAS 3.1). The model keeps the `$ref`, and the path item has no operations (SEC-66-1).
- It resolves a `$ref` to another path item, also in another file, and drops the other fields of the path item. If the target path item also has a `$ref`, swagger-parser follows it and drops the operations next to it (SEC-67-1). For example, `/orders/admin` references `#/webhooks/Admin`, and `Admin` has a `$ref` to `#/webhooks/Inner` and a `get`. The model then has no `GET /orders/admin`.

In both cases the model has other operations than the raw tree. The rules read the raw tree and the gateway routes the operations of the model. Then no rule checks some operations, also no CONTRACT rule, and the gateway builds no route for them. A request to such a path can match a sibling template route, for example `GET /orders/admin` matches `/orders/{id}`. The request then gets the access rule of the template route and goes to the upstream as `/orders/admin`. This is an authorization bypass without a CI signal.

The check reads the raw tree of the root file and of each file that a reference reaches (the files that the reference check reads). In each document, a Path Item Object can be:

- a value of `paths`, at the pointer `/paths/<path>`, for example `/paths/~1orders~1admin`. Fields that start with `x-` are extensions, not path items;
- a value of `webhooks`, at the pointer `/webhooks/<name>`;
- a value of `components/pathItems`, at the pointer `/components/pathItems/<name>`;
- a value of a Callback Object, at the pointer of the callback expression, for example `/paths/~1subscriptions/post/callbacks/onEvent/{$request.body#~1url}`. A Callback Object is a value of the `callbacks` of an operation (in each of these path items), or of `components/callbacks`.

A Callback Object can have a `$ref`. If the `$ref` is local (`#/...`), the check follows it to the target in the same document, at any place, for example `#/x-hooks/OnEvent`. The check reads the target as a Callback Object, at the pointer of the target. It reads the reference text as it is and percent-decoded, so `#/x-hooks/On%20Event` reaches `/x-hooks/On Event`. The check also reads the expressions next to the `$ref`, because a parser can read them too (SEC-67-5). The check reads each Callback Object and each path item once, so a reference cycle ends. A component name, for example `Done`, is `#/components/callbacks/Done`, and the check reads that place anyway.

A Callback Object MUST NOT have a `$ref` to another file, for example `./hooks.yaml#/x-hooks/OnEvent`. The target can be at any place of the other file, and the check does not follow references across files. The loader therefore rejects the reference with an `oas-parse` violation at the pointer of the Callback Object (SEC-69-2). The other file is still read and checked as a referenced file. Define the callback in the same file, for example in `components/callbacks`. No code reads callbacks today.

In a referenced file, the pointer is the pointer of the `$ref` in the root file that leads to that file, as for a forbidden reference. The message names the file and the pointer of the path item in that file.

The message quotes the `$ref` text of the file. It shows each unsafe character as a Java escape, for example `\u0009` (`PrintableText`, see below). It shows at most 200 characters of the text, followed by `...`.

**Operations of the model.** After swagger-parser runs, the loader compares the raw tree with the model, as defense in depth. For each path of `paths` (without `x-` fields), the raw path item and the model path item must exist, and they must have the same set of HTTP methods (`get`, `put`, `post`, `delete`, `options`, `head`, `patch`, `trace`). The model methods come from `PathItem.readOperationsMap()`. Each difference is an `oas-parse` violation at `/paths/<path>`. No known input gives a difference once the loader rejects path item references. Without that rejection, this check alone also rejects the SEC-67-1 chain and each path item reference to a file. The check stops an unknown cause in a later swagger-parser version.

`GatewayRouteBuilder` and `AccessRuleCompiler` also throw `IllegalStateException` for a path item that is still a `$ref` in the model. The gateway then does not start, also if a caller skips the loader.

**Text in messages.** `com.johnowl.gateway.core.text.UnsafeCharacters` is the set of unsafe characters: C0 and C1 control characters, `U+007F`, the Unicode line and paragraph separators `U+2028` and `U+2029`, and the bidirectional controls `U+061C`, `U+200E`, `U+200F`, `U+202A` to `U+202E`, and `U+2066` to `U+2069`. `com.johnowl.gateway.core.text.PrintableText` shows text from the files in a message or a log line: `escape` shows each unsafe character as a Java escape, and `printable` also cuts the result to 200 characters (finding QA-67-4).

**One document.** Jackson and swagger-parser read only the first JSON value, and Jackson reads only the first YAML document. They ignore the rest of the content. A reviewer then reads operations that do not run, or does not see the operations that run (SEC-67-4). The loader therefore rejects content after the first document with an `oas-parse` violation at the document root, before swagger-parser runs:

- JSON: any character after the first JSON value, other than a space, a tab, or a line break. The message gives the line and column of that character. A line break is CR LF, LF, or CR alone.
- JSON in a file whose name does not end with `.json`, for example a `.yaml` file that starts with `{`: a reviewer and an editor read the file as YAML. So the loader also allows comments (`#` to the end of the line) and one line with the YAML document end marker `...` and white space only (decision of QA-69-2). The parsers ignore both. Other text, a second `...` line, and `---` are rejected. A `.json` file allows white space only.
- YAML: a second document, for example after `---`. The message gives the line and column of the second document. One document with the markers `---` and `...` is allowed.

swagger-parser reads content that starts with `{` (after white space) as JSON, also in a `.yaml` file. The loader uses the same test. So a `.yaml` file with a JSON object, `---`, and a YAML document is JSON for both parsers, and the loader rejects the content after the object.

**Byte order mark.** An editor can write a byte order mark (`U+FEFF`) at the start of a file. Jackson does not skip it in JSON text, and SnakeYAML skips it. The loader skips one byte order mark at the start of JSON content, so a `.json` file with a byte order mark loads (QA-69-3). The line and column in a message do not count the byte order mark, and no message contains it.

**Size limits.** A file has at most 5 MB (5,242,880 bytes). swagger-parser reads YAML with the code point limit of SnakeYAML: 3 MB (3,145,728 characters, `DeserializationUtils.Options.maxYamlCodePoints`). A larger YAML file cannot load, so the loader rejects it first with a clear message (QA-69-7). The YAML limit applies to content that does not start with `{`, because swagger-parser reads only this content as YAML. JSON content can have up to 5 MB. The YAML parsers of the loader (Jackson and SnakeYAML) have a code point limit of 5,242,880, so the check of the loader always runs first.

**Key length.** A key has at most 1024 characters (`RawDocuments.MAX_KEY_LENGTH`). A longer key is an `oas-parse` violation at the pointer of the mapping, with the line and column of the key (SEC-69-1). A JSON pointer contains the keys of the path to a node. A rule or a message that copies the pointer then also copies the long key. SnakeYAML already limits a YAML key without `?` to 1024 characters. A key with `?` and a JSON key had no limit. The pre-parse checks (`RawDocuments` and `ReferenceGuard`) also build the text of a pointer only when they report a node (`RawPointer`). Before the fix, a key of 1,000,000 characters above 900,000 nodes took about 50 seconds.

**YAML features.** Jackson builds the raw tree, and swagger-parser uses SnakeYAML. The two parsers read anchors (`&a`), aliases (`*a`), merge keys (`<<`), and explicit tags (`!!int`) in different ways. Then a rule that reads the raw tree and a consumer that reads the model would see different documents. The loader therefore rejects these features with an `oas-parse` violation at the pointer of the node. This also removes the "billion laughs" alias bomb. Referenced files have the same checks and the same size limit.

### `oas-parse` violations

| Condition | Message example | Pointer |
|---|---|---|
| The YAML or JSON syntax is invalid | `Invalid YAML at line 4, column 12: mapping values are not allowed here`. The message does not repeat an unknown token: `Invalid JSON at line 1, column 1: Unrecognized token: was expecting ...`. The token can be a secret. | empty (document root) |
| The document is empty, is not an object, or has no `openapi` or `swagger` field | `Missing field 'openapi'` | empty |
| The file is larger than 5 MB | `The file has 6000000 bytes. The maximum is 5242880 bytes (5 MB)` | empty |
| YAML content is larger than 3 MB | `The YAML file has 4194304 characters. The maximum is 3145728 characters (3 MB)` | empty |
| A key is longer than 1024 characters | `Invalid YAML at line 2, column 3: a key has 2000 characters. The maximum is 1024 characters` | the mapping of the key |
| Content after the first JSON value | `Invalid JSON at line 6, column 1: content after the first JSON value is not allowed` | empty |
| A second YAML document | `Invalid YAML at line 4, column 1: only one YAML document is allowed` | empty |
| A YAML anchor, alias, merge key, or explicit tag | `YAML aliases are not allowed` | the node |
| A forbidden reference | `Reference '../x.yaml' is not allowed: the file is outside the directory of the OAS file` | the `$ref` |
| A path item has `$ref` | `Path item reference '#/components/pathItems/Admin' is not supported. Inline the operations.` | the path item, for example `/paths/~1orders~1admin` |
| A Callback Object has a `$ref` to another file | `Callback reference './hooks.yaml#/x-hooks/OnEvent' to another file is not supported. Define the callback in this file.` | the Callback Object, for example `/paths/~1subscriptions/post/callbacks/onEvent` |
| A path item in a referenced file has `$ref` | `In referenced file 'admin.yaml' at '/components/pathItems/Admin': Path item reference './inner.yaml#/components/pathItems/Inner' is not supported. Inline the operations.` | the `$ref` in the root file that leads to the file |
| The model has other paths or HTTP methods than the raw tree | `The operations of this path item differ from what the parser read.` | the path item, for example `/paths/~1orders` |
| The document has a `swagger` field (Swagger 2.0) | `Unsupported OpenAPI version '2.0'` | `/swagger` |
| The `openapi` field is not 3.0.x or 3.1.x | `Unsupported OpenAPI version '4.0.0'` | `/openapi` |
| swagger-parser reports a message, for example a missing `info` | The swagger-parser message text, for example `attribute info is missing` | empty |
| An internal `$ref` cannot be resolved | `attribute paths.'/orders'(get).responses.200.content.'application/json'.schema.#/components/schemas/DoesNotExist is missing` | empty |
| An external `$ref` cannot be resolved | `Unable to load RELATIVE ref: ./does-not-exist.yaml path: <absolute directory>` | empty |

Every message is one line, so that every output format can show it. The loader joins the lines of a multi-line swagger-parser message with spaces. The line and column of a syntax error come from the Jackson exception. If the `openapi` or `swagger` value is not text, the message shows the value as JSON, for example `Unsupported OpenAPI version '[3]'`. An `openapi` field without a value counts as missing.

swagger-parser messages can contain absolute paths of the machine that runs the parser.

All `oas-parse` violations have severity `ERROR` and category `CONTRACT`. swagger-parser messages do not contain JSON pointers, so the pointer is empty. The rules of [005](005-validation-engine.md) and later specs do not run on a file with an `oas-parse` violation.

### Dependencies

| Dependency | Reason |
|---|---|
| `io.swagger.parser.v3:swagger-parser-v3` `2.1.48` | Parses OAS 3.0 and 3.1, resolves `$ref`. |
| `com.fasterxml.jackson.core:jackson-databind` | Raw `JsonNode`. |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | YAML to `JsonNode`. |

The module uses `swagger-parser-v3`, not the `swagger-parser` aggregator. The aggregator also brings the Swagger 2.0 converter, and this project rejects Swagger 2.0 files.

The Spring Boot BOM does not manage swagger-parser, so the parent `pom.xml` sets the property `swagger-parser.version`. Version 2.1.48 parses OAS 3.1. The test `loadsOas31` verifies this.

swagger-parser uses Jackson 2 (`com.fasterxml.jackson`). Spring Boot 4 uses Jackson 3 (`tools.jackson`), but its BOM also manages Jackson 2. The raw tree in `SpecFile` is therefore a Jackson 2 `JsonNode`, the same type as in the swagger-parser model.

The Maven Enforcer rule `no-spring-in-core` in `openapi-gateway-core/pom.xml` fails the build if the module depends on `org.springframework*` or `io.projectreactor*` in compile, provided, runtime or system scope, directly or transitively. Tests may use Spring, for example to compare `PathSyntax` with `PathPattern` ([007](007-route-model.md)).

## Behavior and errors

| Input | Result |
|---|---|
| Valid OAS 3.0 file | `SpecFile` present, no violations. |
| Valid OAS 3.1 file | `SpecFile` present, no violations. |
| File with invalid YAML | `SpecFile` absent, one `oas-parse` violation. |
| Swagger 2.0 file | `SpecFile` absent, one `oas-parse` violation with pointer `/swagger`. |
| File with an unresolved `$ref` | `SpecFile` absent, at least one `oas-parse` violation. |
| Directory argument that does not exist | `SpecFinder.find` throws `IOException`. |

## Acceptance criteria

- [x] `SpecFinder.find` returns all `.yaml`, `.yml`, and `.json` files of a directory tree, sorted, without duplicates.
- [x] `SpecFinder.find` ignores files with other extensions in a directory, for example `README.md`.
- [x] `SpecFinder.find` throws `IOException` for a path that does not exist.
- [x] `SpecFinder.find` throws `IOException` for a directory that cannot be read.
- [x] `SpecFinder.find` returns a file once if a relative and an absolute argument, or a symbolic link, name the same file.
- [x] `SpecFinder.find` skips symbolic links in a directory search.
- [x] An `oas-parse` message for invalid JSON does not contain the unknown token.
- [x] For a valid OAS 3.0 file and for a valid OAS 3.1 file, `SpecLoader` returns a `SpecFile` with a non-null `OpenAPI` and a non-null raw `JsonNode`.
- [x] `SpecLoader` resolves a relative external `$ref` to a file in the same directory.
- [x] `SpecLoader` rejects URL references without network access, and file references that leave the directory of the root file, also in referenced files and through symbolic links.
- [x] `load(SpecSource)` rejects every reference that is not local.
- [x] `SpecLoader` rejects YAML anchors, aliases, merge keys, and explicit tags, also in referenced files.
- [x] `SpecLoader` rejects a file larger than 5 MB, and YAML content larger than 3 MB (3,145,728 characters), the limit of swagger-parser (QA-69-7).
- [x] `SpecLoader` rejects a key longer than 1024 characters, in JSON and in YAML, also a YAML key with `?`, with the line and column of the key (SEC-69-1).
- [x] The pre-parse checks build the text of a pointer only for a reported node (SEC-69-1).
- [x] `SpecLoader` allows comments and one `...` line after the JSON value in a file whose name does not end with `.json`, and nothing but white space in a `.json` file (QA-69-2).
- [x] `SpecLoader` loads a `.json` file and a `.yaml` file with a byte order mark, and no message contains the byte order mark (QA-69-3).
- [x] The line of a message counts CR LF, LF, and a lone CR as one line break (QA-69-4).
- [x] `SpecLoader` rejects a callback `$ref` to another file, in the root file and in each referenced file (SEC-69-2).
- [x] `SpecLoader` rejects content after the first JSON value, in a `.json` file and in a `.yaml` file that starts with `{`, and a second YAML document, also in referenced files (SEC-67-4). The CLI returns exit code 1, and the gateway does not start.
- [x] `SpecLoader` follows a local callback `$ref` to any place of the same document, and checks the expressions next to it (SEC-67-5).
- [x] `SpecLoader` rejects every path item with `$ref`, local or to a file, in `paths`, `webhooks`, `components/pathItems`, and callbacks, in the root file and in each referenced file (SEC-66-1, SEC-67-1).
- [x] `SpecLoader` rejects the SEC-67-1 chain: `/orders/admin` references `#/webhooks/Admin`, and `Admin` has a `$ref` and a `get`. The CLI returns exit code 1, and the gateway does not start.
- [x] `SpecLoader` rejects a path that has other HTTP methods in the model than in the raw tree.
- [x] `GatewayRouteBuilder` and `AccessRuleCompiler` throw `IllegalStateException` for a path item that is still a `$ref`. The gateway then does not start, also if a caller skips the loader check.
- [x] Every `oas-parse` message is one line.
- [x] Each case in the `oas-parse` table produces at least one violation with rule id `oas-parse` and severity `ERROR`, and no `SpecFile`.
- [x] `SpecLoader` never throws an exception for invalid file content.
- [x] `openapi-gateway-core` still has no dependency on Spring Cloud Gateway or Spring WebFlux.

## Test plan

- Unit tests `SpecFinderTest` with a temporary directory (`@TempDir`): nested directories, mixed extensions, duplicate arguments, missing path.
- Unit tests `SpecLoaderTest` with files in `openapi-gateway-core/src/test/resources/spec-loader/`: OAS 3.0, OAS 3.1, invalid YAML, invalid JSON, missing `info`, Swagger 2.0, missing `openapi`, unresolved internal and external `$ref` in OAS 3.0 and 3.1, relative external `$ref`.
- Unit tests in `SpecLoaderTest` for path item references, each with the exact severity, rule id, pointer, and message: `#/components/pathItems/<name>` in OAS 3.1 and in OAS 3.0, a missing component, a path in the same file (`#/paths/~1orders`), a path item in another file, the SEC-67-1 chain through `webhooks`, references in `webhooks`, `components/pathItems`, `components/callbacks`, and callbacks of operations in `paths` and in `webhooks` (for example `/webhooks/w/post/callbacks/cb/{$url}`), and unsafe characters and a long text in the reference. Callback references (SEC-67-5): a local `$ref` to `#/x-hooks/...`, a percent-encoded `$ref`, a cycle, a missing target, and a `$ref` next to expressions. `followsAPercentEncodedCallbackReference` (QA-69-1): the path item `$ref` is only under `x-hooks/"On Event"`, behind `#/x-hooks/On%20Event`, and the test checks the exact pointer `/x-hooks/On Event/{$url}`.
- Unit test `SpecLoaderTest.reportsEachPathWithOtherOperationsInTheModel`: a model with a missing method, a missing path, and an extra path.
- Unit tests in `SpecLoaderSafetyTest` with a temporary directory: a path item reference to a file, with and without other fields, a reference in a referenced file that drops the operations next to it (SEC-67-1 with files), and a callback reference to a file that has a path item reference. Callback references to another file (SEC-69-2): with a pointer, without a pointer, and in a referenced file.
- `AccessRuleCompilerTest`: a path item `$ref` next to a public template route `/orders/{id}` gives an `oas-parse` violation and no routes. `GatewayRouteBuilderTest` and `AccessRuleCompilerTest`: an unresolved path item in the model throws `IllegalStateException`.
- `ValidateCommandTest`: the CLI returns exit code 1 for a reference to `components/pathItems`, for the SEC-67-1 chain, and for a `.yaml` file with a JSON object, `---`, and a YAML document (SEC-67-4). `OpenApiRoutingStartupTest`: the gateway does not start with these files and with a `.json` file with two objects, and the startup failure escapes unsafe characters (SEC-67-3).
- Unit tests `PrintableTextTest` and `UnsafeCharactersTest`.
- Unit tests `SpecLoaderSafetyTest` with a temporary directory: forbidden references, symbolic links, nested references, YAML features, size limit. Content after the first document (SEC-67-4): a second JSON object and text after the JSON value in a `.json` file, a JSON object with `---` and a YAML document in a `.yaml` file, a second YAML document (also empty, also after `...`), one document with `---` and `...`, and a second YAML document in a referenced file. After the JSON value (QA-69-2, QA-69-4): comments and one `...` line in `.yaml` and `.yml` files, a comment and `...` in a `.json` file, a second `...`, `...` with a comment, an indented `...` before text, and text after CR and CR LF line breaks. A byte order mark in a `.json` file, in a `.yaml` file with JSON, and in a `.yaml` file with YAML, and the messages of four invalid files with a byte order mark (QA-69-3). Keys (SEC-69-1): a key of 1025 characters in a YAML sequence, at the YAML root with `?`, in a JSON array, and at the JSON root, a key of 1024 characters, and a key of 1,000,000 characters above 300,000 nodes, in YAML and in JSON. A YAML file of 4 MB, and a JSON file of 4 MB that loads (QA-69-7).
- Unit test `RawPointerTest`: the pointer text and its escapes, and a child that holds its parent and does not copy the parent text (SEC-69-1).
- The `oas-parse` fixtures for the rule harness (`src/test/resources/rules/oas-parse/`) arrive with [005](005-validation-engine.md).

## Delivery

One PR. Main files: `SpecFinder`, `SpecSource`, `SpecLoader`, `SpecLoadResult`, `SpecFile`, tests, and fixtures. Estimate: fewer than 350 changed lines without fixtures.

`Violation` is defined in [005](005-validation-engine.md). This PR adds `Violation` and `Severity` in the package `com.johnowl.gateway.core.validation` with the fields of [005](005-validation-engine.md). [005](005-validation-engine.md) reuses them and adds `Category` and the other types.

## Review findings

- SEC-66-1 (HIGH): swagger-parser 2.1.48 does not resolve a path item `$ref` to `#/components/pathItems/<name>` in OAS 3.1. No rule checked the operations of such a path item, and the gateway built no route for them. A request then matched a sibling template route with a weaker access rule. Fix: the loader rejects every path item that is still a `$ref` in the model, and `GatewayRouteBuilder` and `AccessRuleCompiler` throw `IllegalStateException` for such a path item. The loader rejects the reference and does not resolve it: own resolution code would be a second reference resolver next to swagger-parser and `ReferenceGuard`.
- SEC-67-1 (HIGH): the fix of SEC-66-1 checked only the model. swagger-parser also resolves a path item `$ref` to a path item that has its own `$ref`, and drops the operations next to that `$ref`. The model then had no `GET /orders/admin`, the CLI returned exit code 0, and the gateway routed `GET /orders/admin` with the public route `/orders/{id}`. Decision: the loader rejects every path item `$ref`, local or to a file, in the raw tree of every loaded document (see "Path item references"). Operations must be inline. As defense in depth, the loader compares the HTTP methods of each path in the raw tree and in the model ("Operations of the model").
- SEC-67-3 (info): the gateway now escapes the file name, the pointer, and the message of a violation in its log and in `OpenApiValidationException` ([013](013-oas-route-locator.md)).
- QA-67-2: the tests check the exact severity, rule id, pointer, and message, and cover a callback in a webhook operation.
- QA-67-3: the message quotes the `$ref` text of the raw file.
- SEC-67-4 (low): Jackson and swagger-parser read only the first JSON value or YAML document and ignored the rest. A `.json` file with a second object, or a `.yaml` file with a JSON object, `---`, and a YAML document, passed the CLI. A reviewer could read a protected operation in the ignored part. Fix: the loader rejects content after the first document before swagger-parser runs, and reads content that starts with `{` as JSON, as swagger-parser does (see "One document"). Before the fix, swagger-parser already rejected two YAML documents when the content did not start with `{`. The loader now gives the line and column.
- SEC-67-5 (info): the path item reference check did not follow a local callback `$ref` to a place outside `components/callbacks`, and skipped a Callback Object with a `$ref` next to expressions. Fix: the check follows each local callback `$ref` in the same document, once for each pointer, without recursion, and also reads the expressions next to the `$ref`.
- QA-67-4: `PrintableText` and `UnsafeCharacters` in `com.johnowl.gateway.core.text` replace the copies of `printable` in `SpecLoader` and `ServersHttpsOnlyRule`.
- QA-69-1: a test proves the percent-decoding of a callback `$ref`. The path item `$ref` is reachable only through `#/x-hooks/On%20Event`.
- QA-69-2 (decision): a `.yaml` or `.yml` file that starts with `{` is JSON for the parsers, but a reviewer and an editor read it as YAML. The loader rejected a YAML comment and the `...` marker after the JSON value. Decision: in a file whose name does not end with `.json`, comments and one `...` line are allowed after the JSON value. A `.json` file stays strict (see "One document").
- QA-69-3: Jackson did not skip a byte order mark in JSON text, so a `.json` file with a byte order mark did not load. The message also contained the byte order mark. Fix: the loader skips one byte order mark at the start of JSON content (see "Byte order mark").
- QA-69-4: the line of a message did not count a lone CR as a line break. Fix: CR LF, LF, and a lone CR each end a line.
- QA-69-7: the documented limit was 5 MB for every file, but swagger-parser stops YAML at 3,145,728 code points with its own message, and Jackson and SnakeYAML in the loader had the same limit. Fix: the loader rejects YAML content larger than 3 MB with its own message, and its YAML parsers have a limit of 5,242,880 code points (see "Size limits"). The limit of swagger-parser is a system property. The loader does not change it, because a system property changes the whole JVM.
- SEC-69-1 (low, existed before PR #69): the YAML check and the reference check built the text of the pointer of each node. A pointer contains each key of the path, so a key of 1,000,000 characters above 900,000 nodes made about 50 seconds of work in the CLI and at gateway startup. Fix: `RawPointer` holds the parent and one key, and the checks build the text only for a reported node. The loader also rejects a key longer than 1024 characters (see "Key length"), so that the rules and messages that copy pointers stay small too.
- SEC-69-2 (info): the check of callback references followed only local references. A callback `$ref` to another file could point to any place of that file, and the check read only the usual places in that file. Fix: the loader rejects a callback `$ref` to another file (see "Path item references").

## Open questions

- Should every swagger-parser message be an `ERROR`? Some messages can be warnings about style, not about structure.
- Should the raw parser reject duplicate keys in YAML (Jackson `STRICT_DUPLICATE_DETECTION`)? A duplicate key can hide an error.
- Can a swagger-parser message be mapped to a JSON pointer? Today the pointer is empty, so the CLI cannot show a line number. Syntax errors already contain the line and column in the message.

## References

- [005 — Validation engine](005-validation-engine.md)
- [006 — x-gateway extension](006-x-gateway-extension.md)
- [008 — CLI basic](008-cli-basic.md)
- [009 — CLI GitHub and JSON output](009-cli-github-and-json-output.md)
- [013 — OAS route locator](013-oas-route-locator.md)
- swagger-parser: https://github.com/swagger-api/swagger-parser
- OpenAPI Specification: https://spec.openapis.org/oas/
