// Arquivo de build raiz: so declara os plugins (sem aplicar em nenhum modulo aqui),
// pra garantir que as versoes fiquem resolvidas e disponiveis pro app/build.gradle.kts.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
