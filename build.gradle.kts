import org.springframework.boot.gradle.tasks.bundling.BootJar

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
	// The shared libs (service-common, event-contracts, and links-contract through it) come from here, and only they:
	// mavenLocal first so a `publishToMavenLocal` from url-shortener-shared-libs wins while iterating locally, GitHub
	// Packages otherwise (the Jenkins build). Reading GitHub Packages needs a token with read:packages, taken from
	// GITHUB_ACTOR and GITHUB_TOKEN or the gpr.user and gpr.token Gradle properties, the same names
	// url-shortener-shared-libs publishes with.
	mavenLocal {
		content {
			includeGroup("io.url-shortener")
		}
	}
	maven {
		name = "GitHubPackages"
		url = uri("https://maven.pkg.github.com/codewithjams-url-shortener/url-shortener-shared-libs")
		content {
			includeGroup("io.url-shortener")
		}
		credentials {
			username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
			password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.token").orNull
		}
	}
	mavenCentral()
}

dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
	}
}

// Deliberately isolated from `main`, matching redirect-service's and url-service's own integrationTest source sets: no
// compile-time dependency on analytics-service's own classes. analytics-service, redirect-service and url-service are
// all treated as HTTP black boxes - "local" launches their real built jars as separate OS processes (see
// LocalEnvironmentConfig), "deployed" just points at configured URLs. redirect-service and url-service are needed only
// to generate the traffic (links, clicks, deletions) that analytics-service consumes.
sourceSets {
	create("integrationTest")
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
	implementation("software.amazon.awssdk:cloudwatch:2.54.13")
	implementation("io.url-shortener:event-contracts:0.1.1")
	implementation("io.url-shortener:service-common:0.1.1")

	// Required by openapi-generator's "spring" output (interfaceOnly): the generated API
	// interface uses Swagger's OpenAPI 3 annotations, and generated models use JsonNullable
	// for optional properties.
	implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
	implementation("org.openapitools:jackson-databind-nullable:0.2.11")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	compileOnly("org.projectlombok:lombok:1.18.48")
	annotationProcessor("org.projectlombok:lombok:1.18.48")

	"integrationTestImplementation"("org.springframework.boot:spring-boot-starter-web")
	"integrationTestImplementation"("org.springframework.boot:spring-boot-starter-test")
	"integrationTestImplementation"("org.springframework.boot:spring-boot-starter-jdbc")
	"integrationTestImplementation"("org.postgresql:postgresql")
	"integrationTestImplementation"("software.amazon.awssdk:dynamodb:2.54.13")
	"integrationTestImplementation"("software.amazon.awssdk:sns:2.54.13")
	"integrationTestImplementation"("software.amazon.awssdk:sqs:2.54.13")
	"integrationTestImplementation"("software.amazon.awssdk:s3:2.54.13")
	"integrationTestImplementation"("software.amazon.awssdk:cloudwatch:2.54.13")
	"integrationTestImplementation"("io.url-shortener:service-common:0.1.1")
	"integrationTestImplementation"("io.floci:testcontainers-floci:1.15.0")
	"integrationTestImplementation"("org.testcontainers:testcontainers")
	"integrationTestImplementation"("org.testcontainers:postgresql")
	"integrationTestImplementation"("org.testcontainers:junit-jupiter")
	"integrationTestImplementation"("org.awaitility:awaitility")
	"integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
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

tasks.register<Test>("localIntegrationTest") {
	description = "Runs the analytics-service integration tests with Testcontainers-backed floci, Postgres and Redis, " +
			"launching the built analytics-service, url-service and redirect-service jars as separate processes " +
			"(spring.profiles.active=local)."
	group = "verification"
	dependsOn(tasks.named("bootJar"))
	testClassesDirs = sourceSets["integrationTest"].output.classesDirs
	classpath = sourceSets["integrationTest"].runtimeClasspath
	systemProperty("spring.profiles.active", "local")
	systemProperty("integration-test.app-jar", tasks.named<BootJar>("bootJar").get().archiveFile.get().asFile.path)
	// url-service and redirect-service live in separate repositories/checkouts, so their built jars can't be reached
	// via a Gradle task dependency the way analytics-service's own bootJar above can. Default to the sibling checkout
	// layout this repo is normally cloned into; override with -PurlServiceJar=... / -PredirectServiceJar=... if yours
	// differs (e.g. a CI workspace). Both must already be built (`./gradlew bootJar` in each).
	val urlServiceJar = (project.findProperty("urlServiceJar") as String?)
			?: "$projectDir/../url-service/build/libs/url-service-0.1.0.jar"
	val redirectServiceJar = (project.findProperty("redirectServiceJar") as String?)
			?: "$projectDir/../redirect-service/build/libs/redirect-service-0.1.0.jar"
	systemProperty("integration-test.url-service-app-jar", urlServiceJar)
	systemProperty("integration-test.redirect-service-app-jar", redirectServiceJar)
}

tasks.register<Test>("integrationTest") {
	description = "Runs the analytics-service integration tests against already-deployed analytics-service, " +
			"url-service and redirect-service instances (spring.profiles.active=deployed). Pass the targets with " +
			"-PbaseUrl=https://... -PurlServiceBaseUrl=https://... -PredirectServiceBaseUrl=https://..."
	group = "verification"
	testClassesDirs = sourceSets["integrationTest"].output.classesDirs
	classpath = sourceSets["integrationTest"].runtimeClasspath
	systemProperty("spring.profiles.active", "deployed")
	systemProperty("integration-test.base-url", (project.findProperty("baseUrl") as String?) ?: "")
	systemProperty("integration-test.url-service-base-url", (project.findProperty("urlServiceBaseUrl") as String?) ?: "")
	systemProperty("integration-test.redirect-service-base-url", (project.findProperty("redirectServiceBaseUrl") as String?) ?: "")
}
