# Regras específicas do Atos Print Bridge.
# Room, Hilt e Compose já publicam suas próprias regras via consumer-rules embutidas nas libs.

# CARREFOUR-PRINT-ANDROID-RELEASE-HARDENING.md §1 — remove logs de debug/verbose do binário
# release. NÃO remove Log.i/w/e: Logger.kt usa esses níveis deliberadamente para diagnóstico em
# produção (ring buffer + DiagnosticsExporter), e essa parte não depende de android.util.Log ter
# sucesso — só a chamada de sistema Log.d/Log.v em si é eliminada, o resto do pipeline de eventos
# (construção da linha JSON, ring buffer) continua rodando normalmente mesmo com esta regra.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static boolean isLoggable(java.lang.String, int);
}
