plugins {
    id("java")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// utf-8
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
}
