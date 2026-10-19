// Eventos de ciclo de vida dos jobs enviados pelo app chamador.
// oneway: o Print Bridge nunca bloqueia esperando o cliente processar o evento.
package br.com.atos.printbridge.api;

oneway interface IPrintBridgeCallback {
    // Chaves em PrintBridgeContract.EVENT_* (event, jobId, groupId, error, page, pageCount...).
    void onJobEvent(in Bundle event);
}
