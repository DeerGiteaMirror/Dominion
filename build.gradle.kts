plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "cn.lunadeer"
version = "2.0.3-beta"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// utf-8
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "com.github.johnrengelman.shadow")

    repositories {
        mavenCentral()
        mavenLocal()
        maven("https://oss.sonatype.org/content/groups/public")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
        maven("https://repo.mikeprimm.com/")
        maven("https://ssl.lunadeer.cn:14454/repository/maven-snapshots/")
    }

    dependencies {
        compileOnly("com.github.BlueMap-Minecraft:BlueMapAPI:v2.6.2")
        compileOnly("us.dynmap:DynmapCoreAPI:3.4")

        implementation("cn.lunadeer:MinecraftPluginUtils:1.3.4-SNAPSHOT")
        implementation("org.yaml:snakeyaml:2.0")
    }

    tasks.processResources {
        // replace @version@ in plugin.yml with project version
        filesMatching("**/plugin.yml") {
            filter {
                it.replace("@version@", rootProject.version.toString())
            }
        }
    }

    tasks.shadowJar {
        archiveClassifier.set("")
        archiveVersion.set(project.version.toString())
        dependsOn(tasks.withType<ProcessResources>())
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":v1_20_1"))
    implementation(project(":v1_21"))
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}

tasks.register("buildPlugin") {
    dependsOn(tasks.getByName("clean"))
    dependsOn(tasks.getByName("shadowJar"))
}