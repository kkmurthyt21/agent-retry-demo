# Third-party software

The runnable JAR packages open-source runtime dependencies. Available copyright notices and license resources are preserved separately under `licenses/`, in addition to the resources retained in the runnable JAR. Maven’s dependency graph identifies exact transitive versions.

| Component | Direct version | Project and license |
|---|---|---|
| H2 Database Engine | 2.4.240 | https://github.com/h2database/h2database — MPL 2.0 / EPL 1.0 dual licensing |
| NetworkNT JSON Schema Validator | 1.5.9 | https://github.com/networknt/json-schema-validator — Apache 2.0 |
| SLF4J NOP binding | 2.0.17 | https://www.slf4j.org/ — MIT |
| Jackson core, databind, annotations, and YAML support | Transitive, pinned by the validator POM | https://github.com/FasterXML/jackson — Apache 2.0 |
| SnakeYAML | Transitive | https://bitbucket.org/snakeyaml/snakeyaml — Apache 2.0 |
| SLF4J API | Transitive | https://www.slf4j.org/ — MIT |
| ITU | Transitive | https://github.com/ethlo/itu — Apache 2.0 |

JUnit Jupiter and Maven plugins are development/test dependencies. They are not required to run the stage commands and are not packaged as runtime test tools in the application JAR.

No AI-model weights, employer materials, customer records, or cloud credentials are included.
