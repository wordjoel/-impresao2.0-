package br.com.atos.printbridge.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch

@Composable
fun AdminPinDialog(
    isCreatingNewPin: Boolean,
    onDismiss: () -> Unit,
    onVerify: suspend (String) -> Boolean,
    onCreate: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreatingNewPin) "Definir PIN administrador" else "PIN administrador") },
        text = {
            Column {
                Text(
                    if (isCreatingNewPin) "Crie um PIN de 4 a 8 dígitos para proteger configurações críticas."
                    else "Digite o PIN para editar configurações críticas.",
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); error = null },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    label = { Text("PIN") },
                )
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isCreatingNewPin) {
                    if (pin.length < 4) {
                        error = "PIN deve ter ao menos 4 dígitos"
                    } else {
                        onCreate(pin)
                        onDismiss()
                    }
                } else {
                    scope.launch {
                        if (onVerify(pin)) {
                            onDismiss()
                        } else {
                            error = "PIN incorreto"
                        }
                    }
                }
            }) { Text("Confirmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
