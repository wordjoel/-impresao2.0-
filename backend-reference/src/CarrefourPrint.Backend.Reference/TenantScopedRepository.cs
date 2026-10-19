namespace CarrefourPrint.Backend.Reference;

/// <summary>
/// Contrato que TODA consulta de recurso comercial deve seguir — CARREFOUR-PRINT-TENANT-ISOLATION.md §1.
/// Note que não existe NENHUM overload que aceite um `tenantId` como parâmetro solto — o único
/// jeito de informar "de qual tenant" é através de um <see cref="AuthContext"/> já validado. Isso
/// não é estilo, é a garantia em si: é estruturalmente impossível chamar este repositório com um
/// tenantId vindo de um campo de requisição não verificado, porque a assinatura do método não
/// aceita um `string tenantId` cru em lugar nenhum.
/// </summary>
public interface ITenantScopedRepository<T> where T : ITenantOwned
{
    /// <summary>Retorna o recurso SE ele pertencer ao tenant de <paramref name="ctx"/> — null em qualquer outro caso, inclusive se o recurso existe mas pertence a outro tenant (nunca 403 revelando existência; ver CARREFOUR-PRINT-TENANT-ISOLATION.md §3 sobre por que 404, não 403).</summary>
    Task<T?> GetByIdAsync(AuthContext ctx, string id, CancellationToken ct = default);

    /// <summary>Lista só os recursos do tenant de <paramref name="ctx"/> — nunca todos os recursos do sistema.</summary>
    Task<IReadOnlyList<T>> ListAsync(AuthContext ctx, CancellationToken ct = default);

    Task InsertAsync(T item, CancellationToken ct = default);
}

/// <summary>
/// Implementação de referência (em memória) — prova a garantia de isolamento sem depender de
/// nenhum banco/framework de backend real (nenhum foi escolhido/provisionado ainda). Uma
/// implementação real (Postgres/Firestore/etc.) deve reproduzir EXATAMENTE esta mesma regra de
/// filtro — este arquivo é o contrato de comportamento a copiar, os testes em
/// TenantIsolationTests.cs são o que qualquer implementação real precisa continuar passando.
/// </summary>
public sealed class InMemoryTenantScopedRepository<T> : ITenantScopedRepository<T> where T : ITenantOwned
{
    private readonly List<T> _items = [];

    public Task<T?> GetByIdAsync(AuthContext ctx, string id, CancellationToken ct = default)
    {
        // A ÚNICA linha que importa neste arquivo inteiro: o filtro por TenantId não é opcional,
        // não é "quando lembrar" — é parte da MESMA condição que filtra por Id. Não existe
        // caminho de código que retorne um item sem essa checagem.
        var match = _items.FirstOrDefault(item => item.Id == id && item.TenantId == ctx.TenantId);
        return Task.FromResult(match);
    }

    public Task<IReadOnlyList<T>> ListAsync(AuthContext ctx, CancellationToken ct = default)
    {
        IReadOnlyList<T> result = _items.Where(item => item.TenantId == ctx.TenantId).ToList();
        return Task.FromResult(result);
    }

    public Task InsertAsync(T item, CancellationToken ct = default)
    {
        _items.Add(item);
        return Task.CompletedTask;
    }
}
