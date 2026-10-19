package br.com.atos.printbridge.application.usecase

import br.com.atos.printbridge.domain.model.Printer
import br.com.atos.printbridge.domain.printing.ConnectionTestResult
import br.com.atos.printbridge.domain.printing.PrinterConnectionTester
import javax.inject.Inject

class TestPrinterConnectionUseCase @Inject constructor(
    private val tester: PrinterConnectionTester,
) {
    suspend operator fun invoke(printer: Printer): ConnectionTestResult = tester.test(printer)
}
