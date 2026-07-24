import com.android.build.api.dsl.LibraryExtension
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.plugins.signing.SigningExtension
import javax.inject.Inject

plugins {
    id("maven-publish")
    id("signing")
}

/** Maven Central coordinates. Verify this GitHub namespace in Central Portal before the first release. */
val publicationGroupId = "io.github.a871521119"
val publicationArtifactId = "viewability"
val publicationVersion = "1.0.0"
val projectUrl = "https://github.com/a871521119/ViewAbility"

group = publicationGroupId
version = publicationVersion

/** Keeps credential validation out of the script closure for Gradle configuration-cache safety. */
abstract class VerifyMavenCentralPublicationTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input @get:Optional abstract val licenseName: Property<String>
    @get:Input @get:Optional abstract val licenseUrl: Property<String>
    @get:Input @get:Optional abstract val portalUsername: Property<String>
    @get:Input @get:Optional abstract val portalPassword: Property<String>
    @get:Input @get:Optional abstract val signingInMemoryKey: Property<String>
    @get:Input abstract val gpgExecutable: Property<String>

    @TaskAction
    fun verify() {
        val requiredValues = listOf(
            "POM_LICENSE_NAME" to licenseName.orNull,
            "POM_LICENSE_URL" to licenseUrl.orNull,
            "mavenCentralUsername" to portalUsername.orNull,
            "mavenCentralPassword" to portalPassword.orNull,
        )
        val missing = requiredValues.filter { it.second.isNullOrBlank() }.map { it.first }
        check(missing.isEmpty()) {
            "Maven Central release is not configured. Missing Gradle properties: ${missing.joinToString()}. " +
                "Set them in ~/.gradle/gradle.properties or your CI secrets."
        }
        if (signingInMemoryKey.orNull.isNullOrBlank()) {
            val gpgAvailable = runCatching {
                execOperations.exec {
                    commandLine(gpgExecutable.get(), "--version")
                    isIgnoreExitValue = true
                }.exitValue == 0
            }.getOrDefault(false)
            check(gpgAvailable) {
                "GnuPG is required for local Maven Central signing. Install it with `brew install gnupg`, " +
                    "then create a signing key with `gpg --full-generate-key`."
            }
        }
    }
}

/** Sends Gradle's completed staging repository to the Central Publisher Portal. */
abstract class UploadMavenCentralDeploymentTask : DefaultTask() {
    @get:Input abstract val portalUsername: Property<String>
    @get:Input abstract val portalPassword: Property<String>
    @get:Input abstract val namespace: Property<String>

    @TaskAction
    fun upload() {
        val bearerToken = Base64.getEncoder().encodeToString(
            "${portalUsername.get()}:${portalPassword.get()}".toByteArray(),
        )
        val endpoint = URI(
            "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/" +
                "${namespace.get()}?publishing_type=user_managed",
        ).toURL()
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        val responseCode = connection.responseCode
        val response = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        check(responseCode in 200..299) {
            "Maven Central Portal upload failed (HTTP $responseCode): $response"
        }
        logger.lifecycle(
            "Maven Central deployment submitted. Review and publish it at " +
                "https://central.sonatype.com/publishing/deployments\n$response",
        )
    }
}

extensions.configure<LibraryExtension> {
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

extensions.configure<PublishingExtension> {
    publications {
        register<MavenPublication>("release") {
            groupId = publicationGroupId
            artifactId = publicationArtifactId
            version = publicationVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("ViewAbility")
                description.set("Android SDK for viewability and continuous impression measurement.")
                url.set(projectUrl)

                // Publishing is blocked until the project owner explicitly chooses a license.
                val licenseName = providers.gradleProperty("POM_LICENSE_NAME").orNull
                val licenseUrl = providers.gradleProperty("POM_LICENSE_URL").orNull
                if (!licenseName.isNullOrBlank() && !licenseUrl.isNullOrBlank()) {
                    licenses {
                        license {
                            name.set(licenseName)
                            url.set(licenseUrl)
                        }
                    }
                }

                developers {
                    developer {
                        id.set("a871521119")
                        name.set("fren")
                        url.set("https://github.com/a871521119")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/a871521119/ViewAbility.git")
                    developerConnection.set("scm:git:ssh://git@github.com/a871521119/ViewAbility.git")
                    url.set(projectUrl)
                }
            }
        }
    }

    repositories {
        maven {
            name = "MavenCentral"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials(PasswordCredentials::class) {
                username = providers.gradleProperty("mavenCentralUsername").orNull
                password = providers.gradleProperty("mavenCentralPassword").orNull
            }
        }
    }
}

val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
extensions.configure<SigningExtension> {
    if (!signingKey.isNullOrBlank()) {
        // CI can provide an ASCII-armored key through an encrypted secret.
        useInMemoryPgpKeys(
            signingKey,
            providers.gradleProperty("signingInMemoryKeyPassword").orNull,
        )
    } else {
        // Local releases use the user's GnuPG keyring, keeping the private key out of Gradle files.
        useGpgCmd()
    }
    sign(extensions.getByType<PublishingExtension>().publications)
}

val verifyMavenCentralPublication = tasks.register<VerifyMavenCentralPublicationTask>("verifyMavenCentralPublication") {
    group = "publishing"
    description = "Verifies Maven Central credentials, signing, and license metadata."
    notCompatibleWithConfigurationCache("Reads Maven Central and signing secrets from Gradle properties.")
    licenseName.set(providers.gradleProperty("POM_LICENSE_NAME"))
    licenseUrl.set(providers.gradleProperty("POM_LICENSE_URL"))
    portalUsername.set(providers.gradleProperty("mavenCentralUsername"))
    portalPassword.set(providers.gradleProperty("mavenCentralPassword"))
    signingInMemoryKey.set(providers.gradleProperty("signingInMemoryKey"))
    gpgExecutable.set(providers.gradleProperty("signing.gnupg.executable").orElse("gpg"))
}

tasks.matching { it.name == "publishReleasePublicationToMavenCentralRepository" }.configureEach {
    dependsOn(verifyMavenCentralPublication)
}

tasks.register<UploadMavenCentralDeploymentTask>("publishToMavenCentral") {
    group = "publishing"
    description = "Uploads the signed release to the Maven Central Portal for manual approval."
    notCompatibleWithConfigurationCache("Uploads a credential-protected Maven Central deployment.")
    dependsOn("publishReleasePublicationToMavenCentralRepository")
    portalUsername.set(providers.gradleProperty("mavenCentralUsername"))
    portalPassword.set(providers.gradleProperty("mavenCentralPassword"))
    namespace.set(publicationGroupId)
}
