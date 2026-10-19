import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// keystore.properties nunca é versionado (ver .gitignore) — ausente em qualquer máquina que não
// seja a de quem gerou o keystore de release. Nesse caso o signingConfig fica sem storeFile e o
// assembleRelease falha com um erro claro, em vez de fingir sucesso com build não assinada.
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// D-EMU-1 (homologação em emulador): chave PÚBLICA de teste do gateway local, só para a debug.
// Nunca a privada: só caracteres de Base64 de uma SPKI. Qualquer tarefa de release na mesma
// execução com a propriedade presente FALHA a build (separação debug/release garantida no Gradle).
val emuLicenseTestPublicKey: String = (findProperty("pb.debug.licenseTestPublicKey") as String?)?.trim().orEmpty()
if (emuLicenseTestPublicKey.isNotEmpty() && !Regex("^MFkwEwYH[A-Za-z0-9+/]{80,120}={0,2}$").matches(emuLicenseTestPublicKey)) {
    throw GradleException("D-EMU-1: pb.debug.licenseTestPublicKey não é uma chave PÚBLICA EC P-256 (SPKI Base64).")
}
gradle.taskGraph.whenReady {
    if (emuLicenseTestPublicKey.isNotEmpty() && allTasks.any { it.name.contains("Release") }) {
        throw GradleException(
            "D-EMU-1: pb.debug.licenseTestPublicKey informada junto com tarefa de RELEASE " +
                "(${allTasks.first { it.name.contains("Release") }.path}). Release usa só a chave de produção.",
        )
    }
}

android {
    namespace = "br.com.atos.printbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.atos.printbridge"
        minSdk = 24
        targetSdk = 35
        // Distribuicao em escala (MDM): o Android so aceita atualizacao com versionCode MAIOR.
        // O build de release informa a versao: gradlew assembleRelease -PappVersionCode=2 -PappVersionName=1.0.1
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file("keystore/${keystoreProperties["storeFile"]}")
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8 (minify) + resource shrinking — CARREFOUR-PRINT-ANDROID-RELEASE-HARDENING.md §1.
            // NUNCA o mecanismo de proteção da licença (essa é a autoridade do servidor + tokens
            // assinados, CARREFOUR-PRINT-SERVER-LICENSING.md) — só redução de tamanho/superfície
            // e fricção básica contra engenharia reversa casual, nada além disso.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // D-EMU-1: release NUNCA recebe chave de teste (campo fixo vazio).
            buildConfigField("String", "LICENSE_TEST_PUBLIC_KEY_SPKI_B64", "\"\"")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            // D-EMU-1: só a debug aceita a chave PÚBLICA de teste do gateway local (homologação
            // em emulador). Vazia por padrão = chave de produção também na debug.
            buildConfigField("String", "LICENSE_TEST_PUBLIC_KEY_SPKI_B64", "\"$emuLicenseTestPublicKey\"")
        }
    }

    // Uma versão por cliente. O applicationId é o MESMO em todos os flavors, de propósito: os apps
    // clientes da API se ligam ao pacote fixo (PrintBridgeClient.DEFAULT_PACKAGE), a permissão
    // signature, a licença por aparelho e a config gerenciada do MDM são todas indexadas pelo
    // pacote. Cada loja roda um cliente só; o flavor muda marca, nome no launcher e nome do APK.
    // Cliente novo: novo create(...) aqui + BrandId/BrandPresets + src/<flavor>/res/values/strings.xml.
    flavorDimensions += "cliente"
    productFlavors {
        create("carrefour") {
            dimension = "cliente"
            isDefault = true
            buildConfigField("String", "CLIENT_BRAND_KEY", "\"carrefour\"")
            buildConfigField("boolean", "BRAND_LOCKED", "true")
        }
        create("roldao") {
            dimension = "cliente"
            // "rodao" é a chave já persistida nos aparelhos (BrandId.RODAO); não corrigir a grafia.
            buildConfigField("String", "CLIENT_BRAND_KEY", "\"rodao\"")
            buildConfigField("boolean", "BRAND_LOCKED", "true")
        }
        create("assai") {
            dimension = "cliente"
            buildConfigField("String", "CLIENT_BRAND_KEY", "\"assai\"")
            buildConfigField("boolean", "BRAND_LOCKED", "true")
        }
        create("generico") {
            dimension = "cliente"
            // Demonstração/laboratório: marca ATHOS, troca de marca liberada em Configurações.
            buildConfigField("String", "CLIENT_BRAND_KEY", "\"generic\"")
            buildConfigField("boolean", "BRAND_LOCKED", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 não tem java.util.Base64 (API 26+) nativamente — EcdsaLicenseTokenVerifier
        // (CARREFOUR-PRINT-SERVER-LICENSING.md §5) usa Base64 puro do JDK de propósito, para ser
        // testável em JVM pura sem Robolectric. Desugaring resolve isso sem reescrever Base64 à
        // mão nem trocar para android.util.Base64 (que exigiria Robolectric para testar).
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Integração D2 com o gateway local do MDM: fixture (token de TESTE + chave pública)
            // em -Ppb.it.fixture=<arquivo>. Vazio = ManagedLicenseGatewayTokenIntegrationTest ignorado.
            all { test ->
                test.systemProperty("pb.it.fixture", (project.findProperty("pb.it.fixture") as String?) ?: "")
            }
        }
    }

    lint {
        // Não é uma supressão de regra: com AGP 8.6 + Kotlin 2.0 o lint ABORTA a análise dos
        // fontes de teste com um bug interno do analisador K2/FIR ("Unexpected failure during lint
        // analysis ... FirRegularClassImpl from RAW_FIR to SUPER_TYPES"), que atinge todos os
        // arquivos de teste do módulo de uma vez e não aponta nenhum defeito real. O lint continua
        // cobrindo o código de produção (onde detecta erro de verdade); os fontes de teste são
        // cobertos por `:app:testDebugUnitTest`.
        checkTestSources = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":printbridge-api"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // D2 (Fase D, 14/10/2026): sem agente MDM embutido. OkHttp, CameraX e ML Kit eram usados só
    // pelo agente e pela matrícula por QR, que saíram. O identificador de licença vai ao agente
    // oficial (com.onmdm.agent) pelo canal Android Enterprise de Keyed App States.
    implementation("androidx.enterprise:enterprise-feedback:1.1.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("androidx.room:room-testing:2.6.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// C2 — gate automatizado de build (CARREFOUR-CYBER-SECURITY-GATE.md §1/C2): nenhum artefato de
// release pode carregar a chave pública de TESTE do servidor de licenciamento, cuja privada
// correspondente está publicada em texto claro na documentação deste repositório — qualquer um com
// acesso a ela consegue forjar uma licença válida offline, para sempre, sem nunca contatar o
// backend (quebra total do modelo de licenciamento). Se a chave for detectada no APK de release,
// o build FALHA. Valor espelhado em TEST_SERVER_PUBLIC_KEY_SPKI_BASE64 (LicenseModule.kt) — se um
// lado mudar sem o outro, o gate buga de forma audível, não silenciosa.
val testLicensePublicKeyBase64 =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEW+9sz+y5lSVdiKblHDTp9w2t4p3/OiGGKPL90oUYTQp/" +
        "aKBEpivJfg65xak1/xtI7bXHsE/XmDbOE73VpGk1dQ=="

val verifyNoTestLicenseKeyInRelease by tasks.registering {
    group = "verification"
    description = "Falha o release se o APK contiver a chave pública de TESTE do licenciamento (C2)."

    doLast {
        // Override explícito, nunca implícito: permite empacotar um APK de homologação/demo com a
        // chave de teste quando QUEM ASSINA o release decide conscientemente — passa a
        // responsabilidade para o runtime do build, igual ao "aceito por escrito, nunca por omissão".
        val override = providers.gradleProperty("allowTestLicenseKeyInRelease")
            .map { it.trim().equals("true", ignoreCase = true) }
            .getOrElse(false)
        if (override) {
            logger.warn("C2: allowTestLicenseKeyInRelease=true — chave de TESTE autorizada neste release (override explícito).")
            return@doLast
        }

        // Com flavors o AGP grava em outputs/apk/<cliente>/release/; varre todos os clientes.
        val releaseApks = fileTree(layout.buildDirectory.dir("outputs/apk")) { include("**/release/*.apk") }.files
        if (releaseApks.isEmpty()) {
            throw GradleException("C2: nenhum APK de release encontrado para verificar a chave de licença.")
        }

        releaseApks.forEach { apk ->
            // APK é um ZIP: classes.dex fica comprimido, então escanear o byte-append cru do arquivo
            // é sempre falso-negativo. Ler cada entrada DEScomprimida e varrer é o único jeito de,
            // de fato, ver o que vai no binário.
            ZipFile(apk).use { zip: ZipFile ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val content = String(zip.getInputStream(entry).readAllBytes(), Charsets.US_ASCII)
                    if (content.contains(testLicensePublicKeyBase64)) {
                        throw GradleException(
                            "C2: o APK '${apk.name}' (entrada '${entry.name}') contém a chave pública de " +
                                "TESTE do servidor de licenciamento. A privada correspondente está publicada na " +
                                "documentação — não assine nem distribua release com essa chave. Substitua " +
                                "TEST_SERVER_PUBLIC_KEY_SPKI_BASE64 em LicenseModule.kt pela chave pública real do " +
                                "backend antes do release. Para empacotar um release de homologação com a chave " +
                                "de teste de forma CONSCIENTE, rode com -PallowTestLicenseKeyInRelease=true.",
                        )
                    }
                }
            }
        }
    }
}

// `assembleRelease` só existe após a variante release ser materializada pelo AGP — registrar a
// dependência direto no corpo do script falharia em configuração; afterEvaluate é onde o
// androidComponents já conhece a tarefa. Com flavors, cobre também assemble<Cliente>Release:
// gerar o release de um cliente só não pode escapar do gate.
afterEvaluate {
    tasks.matching { it.name.startsWith("assemble") && it.name.endsWith("Release") }.configureEach {
        finalizedBy(verifyNoTestLicenseKeyInRelease)
    }
}
