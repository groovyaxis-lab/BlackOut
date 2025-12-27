plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
}

base {
    archivesName = properties["archives_base_name"] as String
    group = properties["maven_group"] as String
    version = properties["mod_version"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    flatDir {
        dirs("libs")
    }
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:${properties["minecraft_version"] as String}")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")

    // Meteor
    modImplementation("meteordevelopment:meteor-client:${properties["meteor_version"] as String}-SNAPSHOT")

    // Baritone (local nightly build for 1.21.11)
    modCompileOnly(files("libs/baritone-api-fabric-1.15.0-2-gf7a53504.jar"))
}

loom {
    accessWidenerPath = file("src/main/resources/blackout.accesswidener")

    runConfigs.all {
        ideConfigGenerated(true)
        vmArgs("-Xmx2G", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC")
        // Fix for AMD GPUs and render system initialization issues
        vmArgs("-Dloader.gameJarPath.inject=true")
    }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to project.property("minecraft_version")
        )

        inputs.properties(propertyMap)

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    withType<JavaCompile> {
        options.release = 21
        options.encoding = "UTF-8"
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
