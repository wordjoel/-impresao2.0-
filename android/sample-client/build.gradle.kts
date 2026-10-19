// App cliente de TESTE da API local do Print Bridge (Fase 1, Etapa 6): simula um app ATHOS no
// coletor chamando printJob() com template, PDF, imagem e ZPL bruto, e mostra status/callbacks.
// Precisa ser assinado com a MESMA chave do Print Bridge instalado (permissão signature):
// debug com debug, release com o keystore de release.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.atos.printbridge.sampleclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.atos.printbridge.sampleclient"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":printbridge-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
