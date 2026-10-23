# Third-party software

The runnable JAR packages open-source runtime dependencies. Available copyright notices and license resources are preserved separately under `licenses/`, in addition to the resources retained in the runnable JAR. Maven’s dependency graph identifies exact transitive versions.

| Component | Direct version | Project and license |
|---|---|---|
| Spring AI Google GenAI and framework modules | 2.0.1 | https://github.com/spring-projects/spring-ai — Apache 2.0 |
| Google GenAI Java SDK | 1.65.0, selected by Spring AI | https://github.com/googleapis/java-genai — Apache 2.0 |
| Spring Framework | 7.0.9, selected by Spring AI | https://github.com/spring-projects/spring-framework — Apache 2.0 |
| H2 Database Engine | 2.4.240 | https://github.com/h2database/h2database — MPL 2.0 / EPL 1.0 dual licensing |
| NetworkNT JSON Schema Validator | 1.5.9 | https://github.com/networknt/json-schema-validator — Apache 2.0 |
| SLF4J NOP binding | 2.0.17 | https://www.slf4j.org/ — MIT |
| Jackson 2 and Jackson 3, including shared annotations | Jackson 3.1.5 BOM; see runtime dependency inventory | https://github.com/FasterXML/jackson — Apache 2.0 |
| SnakeYAML | Transitive | https://bitbucket.org/snakeyaml/snakeyaml — Apache 2.0 |
| SLF4J API | Transitive | https://www.slf4j.org/ — MIT |
| ITU | Transitive | https://github.com/ethlo/itu — Apache 2.0 |

JUnit Jupiter and Maven plugins are development/test dependencies. They are not required to run the stage commands and are not packaged as runtime test tools in the application JAR.

No AI-model weights, employer materials, customer records, or cloud credentials are included.

The Spring AI integration adds runtime dependencies including Google authentication and HTTP clients, Micrometer, Reactor, Victools schema tools, Kotlin runtime components and their transitive dependencies. The complete runtime inventory and the license/notice resources found in those JARs are included under licenses/runtime. The runnable JAR merges service-provider resources.
