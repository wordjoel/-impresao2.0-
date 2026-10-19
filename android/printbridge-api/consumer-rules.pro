# O contrato viaja pelo Binder por nome (descritor AIDL) e por chaves de Bundle em string:
# nada aqui pode ser renomeado pelo R8 de quem consome a biblioteca.
-keep class br.com.atos.printbridge.api.** { *; }
