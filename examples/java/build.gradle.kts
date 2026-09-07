plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.victools:jsonschema-generator:4.37.0")
    implementation("com.github.victools:jsonschema-module-jackson:4.37.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.0")
}

application {
    mainClass.set("GenerateSchema")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
