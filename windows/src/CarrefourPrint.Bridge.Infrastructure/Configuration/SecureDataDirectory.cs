using System.Runtime.Versioning;
using System.Security.AccessControl;
using System.Security.Principal;

namespace CarrefourPrint.Bridge.Infrastructure.Configuration;

/// <summary>
/// Restringe o diretório de dados do Bridge (%ProgramData%\CarrefourPrint\Bridge — configuração,
/// license.json, bridge.db, diagnósticos) a Administradores e SYSTEM — CARREFOUR-PRINT-DATA-CLASSIFICATION.md
/// §4. Sem isso, o ACL padrão herdado de %ProgramData% normalmente concede a qualquer usuário
/// local autenticado permissão de leitura, o que exporia o `LicenseState.rawToken` e o payload de
/// `PrintJob` (ambos classificados CONFIDENTIAL/acima) a qualquer conta não-administrativa na
/// mesma máquina — um risco real em um coletor/PC compartilhado de loja.
/// </summary>
[SupportedOSPlatform("windows")]
public static class SecureDataDirectory
{
    public static void Protect(string path)
    {
        Directory.CreateDirectory(path);

        var directoryInfo = new DirectoryInfo(path);
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false); // remove herança do %ProgramData% pai

        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None,
            AccessControlType.Allow));

        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None,
            AccessControlType.Allow));

        // Sem isto, um serviço rodando sob uma conta dedicada (nem Administrators, nem
        // LocalSystem) — ou mesmo uma execução interativa de desenvolvimento sob um usuário
        // comum — perderia acesso à própria pasta que acabou de criar. Confirmado por teste real
        // nesta sessão: a primeira versão (só Administrators+SYSTEM) quebrou a abertura do
        // bridge.db na conta usada para rodar o smoke test. "Proteger contra os outros usuários
        // da máquina" nunca deve significar "proteger contra quem está rodando o processo".
        var currentIdentity = WindowsIdentity.GetCurrent().User;
        if (currentIdentity is not null)
        {
            security.AddAccessRule(new FileSystemAccessRule(
                currentIdentity,
                FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None,
                AccessControlType.Allow));
        }

        directoryInfo.SetAccessControl(security);
    }
}
