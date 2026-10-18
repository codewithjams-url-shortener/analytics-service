plugins {
	java
	id("org.springframework.boot") version "3.5.6"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.openapi.generator") version "7.25.0"
}

group = "io.url-shortener"
version = "0.1.0"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
	mavenLocal()
}

dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("io.micrometer:micrometer-tracing-bridge-otel")
	implementation("io.opentelemetry:opentelemetry-exporter-otlp")
	implementation("org.postgresql:postgresql")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("software.amazon.awssdk:sqs:2.54.13")
	implementation("software.amazon.awssdk:s3:2.54.13")
	implementation("software.amazon.awssdk:dynamodb:2.54.13")
	implementation("io.url-shortener:event-contracts:0.1.0")
	implementation("io.url-shortener:service-common:0.1.0")

	// Required by openapi-generator's "spring" output (interfaceOnly): the generated API
	// interface uses Swagger's OpenAPI 3 annotations, and generated models use JsonNullable
	// for optional properties.
	implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
	implementation("org.openapitools:jackson-databind-nullable:0.2.11")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	compileOnly("org.projectlombok:lombok:1.18.48")
	annotationProcessor("org.projectlombok:lombok:1.18.48")

	testImplementation("io.floci:testcontainers-floci:1.15.0")
	testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

openApiGenerate {
	val basePackage = "io.urlshortener.analyticsservice"
	generatorName.set("spring")
	inputSpec.set("$projectDir/src/main/resources/openapi/openapi.yaml")
	outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.path)
	apiPackage.set("$basePackage.controller")
	modelPackage.set("$basePackage.model.dataTransferObject")
	configOptions.apply {
		put("interfaceOnly", "true")
		put("useSpringBoot3", "true")
		put("generateBuilders", "true")
		// Without this, interfaces are named/grouped by path segment (e.g. "ShortCodeApi" for
		// /{shortCode}), ignoring the operation's own `tags` entirely.
		put("useTags", "true")
	}
}

sourceSets.main {
	java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
}

tasks.compileJava {
	dependsOn(tasks.openApiGenerate)
}
