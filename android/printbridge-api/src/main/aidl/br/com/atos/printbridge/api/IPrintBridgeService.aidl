// API local do ATHOS Print Bridge (Fase 1). Bind com a action
// br.com.atos.printbridge.action.BIND_PRINT_BRIDGE; exige a permissão (signature)
// br.com.atos.printbridge.permission.SUBMIT_PRINT_JOB.
//
// Todas as chamadas são síncronas no Binder: chame fora da main thread
// (printJob de PDF rasteriza o documento antes de responder).
//
// Resultados vêm em Bundle: ok=true + dados, ou ok=false + errorCode/errorMessage
// (códigos estáveis em PrintBridgeContract.ERROR_*). Nenhuma chamada lança exceção
// pelo Binder por erro de negócio.
package br.com.atos.printbridge.api;

import br.com.atos.printbridge.api.IPrintBridgeCallback;

interface IPrintBridgeService {
    int getApiVersion();

    // request = PrintJobRequest.toBundle(). Resultado: jobId (ou id do documento, se PDF
    // multipágina) + pageCount + printerId. O job já está persistido na fila ao retornar.
    Bundle printJob(in Bundle request);

    // Resultado: ok + JobStatus.toBundle() (state=QUEUED|SENDING|COMPLETED|FAILED|PARTIALLY_COMPLETED|CANCELLED).
    Bundle getJobStatus(String jobId);

    // profile = PrinterProfile.toBundle(). Resultado: ok + printerId.
    Bundle registerPrinterProfile(in Bundle profile);

    // storeId vazio = todas as impressoras do aparelho.
    List<Bundle> listPrinterProfiles(String storeId);

    void registerCallback(IPrintBridgeCallback callback);
    void unregisterCallback(IPrintBridgeCallback callback);
}
