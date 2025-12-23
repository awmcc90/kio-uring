plugins {
    kotlin("jvm") version "2.3.0"
    id("me.champeau.jmh") version "0.7.2"
    `java-library`
    `maven-publish`
}

group = "io.kiouring"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api("io.netty:netty-buffer:4.2.9.Final")
    api("io.netty:netty-transport:4.2.9.Final")
    api("io.netty:netty-transport-native-io_uring:4.2.9.Final:linux-x86_64")

    implementation("org.apache.logging.log4j:log4j-api:2.25.3")
    implementation("org.apache.logging.log4j:log4j-api-kotlin:1.5.0")

    runtimeOnly("org.apache.logging.log4j:log4j-core:2.25.3")
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.3")
    runtimeOnly("com.lmax:disruptor:4.0.0")

    testImplementation(kotlin("test"))
}

jmh {
    jvmArgs.addAll(
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms1G",
        "-Xmx1G",
        "-XX:+AlwaysPreTouch",
        "-XX:MaxDirectMemorySize=2G",
        "-Dio.netty.tryReflectionSetAccessible=true",
        "-Dio.netty.iouring.ringSize=4096",
        "-Dio.netty.noUnsafe=false",
        "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector",
    )
}

sourceSets {
    create("example").also {
        it.compileClasspath += sourceSets.main.get().output
        it.runtimeClasspath += sourceSets.main.get().output
    }
}

configurations["exampleImplementation"].extendsFrom(configurations.implementation.get())
configurations["exampleRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runExample") {
    group = "application"
    description = "Run the basic example"
    classpath = sourceSets["example"].runtimeClasspath
    mainClass.set("example.MainKt")

    systemProperty("io.netty.iouring.ringSize", "32768")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("kio-uring")
                description.set("Kotlin async file I/O library using io_uring via Netty")
                url.set("https://github.com/awmcc90/kio-uring")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
