# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
该项目基于开源的 xwiki 进行二开，主要是用于替换掉公司的 Confluence

## Build Commands

### Java (Maven)

The parent POM is `org.xwiki.commons:xwiki-commons-pom` (version 18.1.0). XWiki Commons is a sibling project at `../xwiki-commons` and must be built first or resolved from Maven repositories.

**Fast build (skip all checks and tests):**
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true install
```

**Build a specific module and its dependencies:**
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl <module-path> -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true install
```

**Run a single test class:**
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl <module-path> -Dtest=MyTestClass test
```

**Run a single test method:**
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl <module-path> -Dtest=MyTestClass#myMethod test
```

### JavaScript/TypeScript (pnpm + Nx)

Frontend code lives under `xwiki-platform-core/xwiki-platform-node/src/main/node/` and a few other `*-webjar` modules. All are managed via pnpm workspaces and Nx.

```bash
# In the root or any workspace package:
pnpm run lint           # ESLint for all JS/TS packages
pnpm run test:unit      # Vitest for all packages
pnpm run build          # Build all packages via Nx
pnpm run typecheck      # TypeScript type checking only
```

For a single package, use Nx directly:
```bash
npx nx build @xwiki/<package-name>
npx nx test @xwiki/<package-name>
```

### Local Tomcat Deployment

1. Build the relevant module JARs
2. Copy JARs to Tomcat: `cp target/<jar>.jar /Users/noaharno/programer/software/xwiki/apache-tomcat-11.0.22/webapps/xwiki/WEB-INF/lib/`
3. Restart Tomcat: `shutdown.sh` then `startup.sh`
4. Verify at `http://localhost:8080/xwiki/`

## Architecture

### Module Organization

- **`xwiki-platform-core/`** — ~80+ modules, each a self-contained Maven submodule. Follows the `xwiki-platform-<feature>` naming convention (e.g., `xwiki-platform-security`, `xwiki-platform-rest`). Each feature module typically splits into:
  - `xwiki-platform-<feature>-api` — Java interfaces and models
  - `xwiki-platform-<feature>-default` — default implementation
  - `xwiki-platform-<feature>-ui` — Velocity templates / XAR resources / web UI
  - `xwiki-platform-<feature>-test` — test helpers (optional)
  - `xwiki-platform-<feature>-webjar` — bundled frontend assets (optional)

- **`xwiki-platform-tools/`** — Maven plugins and build-time tools (e.g., packager, provisioner, Jetty runner).

- **`xwiki-platform-distribution/`** — packaging: WAR assembly, flavor definitions, Debian packaging, migration scripts.

### Technology Stack

- **Backend**: Java, Maven, XWiki Commons (component-based DI), Hibernate 5.6, Solr/Lucene for search, Velocity for server-side templating
- **Frontend**: TypeScript, Vue 3, React (BlockNote editor), Vite, Vitest, pnpm workspaces with Nx task orchestration
- **Component wiring**: XWiki uses a custom component manager. Components are declared in `META-INF/components.txt` files within each JAR. If you add a new component class, you must register it there.

### Key Patterns

- **Component registration**: Every component implementation must be listed in its module's `src/main/resources/META-INF/components.txt`. Missing entries cause runtime errors.
- **XAR modules**: UI resources (wiki pages, macros, panels) are packaged as XAR (XWiki ARchive) files. These are ZIPs containing XML page definitions.
- **Tests use `xwiki-commons-tool-test-simple`** as a base test framework (configured automatically via Surefire/Failsafe in the parent POM).
- **Integration tests** live in modules matching `*-test-tests` or `*-test-docker` patterns and use the Failsafe plugin.

### Git Workflow

- Main branch: `master`
- Issue tracker: JIRA at https://jira.xwiki.org/browse/XWIKI
- CI: https://ci.xwiki.org/
- Development practices: https://dev.xwiki.org/xwiki/bin/view/Community/DevelopmentPractices
