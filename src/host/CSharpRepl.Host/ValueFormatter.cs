using System.Collections;
using System.Globalization;
using System.Reflection;
using System.Text;

namespace CSharpRepl.Host;

/// <summary>A member value whose getter (or enumeration) threw; displayed as a value instead of failing the expansion.</summary>
public sealed record ValueError(Exception Exception);

/// <summary>A plain text entry in a children list (the "…" row); displayed unquoted, never expandable.</summary>
public sealed record Note(string Text);

/// <summary>Debugger-like display strings and lazily expandable children. Never throws, never enumerates in <see cref="Display"/>.</summary>
public static class ValueFormatter
{
    private const int MaxLength = 1000;
    private const int MaxItems = 100;

    private static readonly Dictionary<Type, string> Aliases = new()
    {
        [typeof(bool)] = "bool", [typeof(byte)] = "byte", [typeof(sbyte)] = "sbyte", [typeof(char)] = "char",
        [typeof(decimal)] = "decimal", [typeof(double)] = "double", [typeof(float)] = "float", [typeof(int)] = "int",
        [typeof(uint)] = "uint", [typeof(long)] = "long", [typeof(ulong)] = "ulong", [typeof(short)] = "short",
        [typeof(ushort)] = "ushort", [typeof(object)] = "object", [typeof(string)] = "string", [typeof(void)] = "void",
    };

    public static string TypeName(Type t)
    {
        if (Aliases.TryGetValue(t, out var alias)) return alias;
        if (t.IsArray) return TypeName(t.GetElementType()!) + "[" + new string(',', t.GetArrayRank() - 1) + "]";
        if (!t.IsGenericType) return t.Name;
        var args = string.Join(", ", t.GetGenericArguments().Select(TypeName));
        return t.GetGenericTypeDefinition() == typeof(Nullable<>) ? args + "?" : $"{t.Name[..t.Name.IndexOf('`')]}<{args}>";
    }

    public static string Display(object? v)
    {
        try
        {
            var s = DisplayCore(v);
            return s.Length > MaxLength ? s[..MaxLength] + "…" : s;
        }
        catch (Exception e) { return Failure(e); }
    }

    private static string DisplayCore(object? v)
    {
        switch (v)
        {
            case null: return "null";
            case Note n: return n.Text;
            case ValueError e: return Failure(e.Exception);
            case Exception ex: return Failure(ex);
            case string s:
                var cut = s.Length > MaxLength - 3;
                return "\"" + Escape(cut ? s[..(MaxLength - 3)] : s, '"') + (cut ? "…" : "") + "\"";
            case char c: return "'" + Escape(c.ToString(), '\'') + "'";
            case bool b: return b ? "true" : "false";
            case IFormattable f: return f.ToString(null, CultureInfo.InvariantCulture);
        }

        if (TryCount(v, out var count)) return $"Count = {count}";
        var t = v.GetType();
        if (v is IEnumerable)
        {
            // Compiler iterators / LINQ internals are not nameable; show the interface the user sees.
            var shown = !t.IsVisible ? t.GetInterfaces().FirstOrDefault(i => i.IsGenericType && i.GetGenericTypeDefinition() == typeof(IEnumerable<>)) : null;
            return "{" + TypeName(shown ?? t) + "}";
        }

        var owner = t.GetMethod("ToString", Type.EmptyTypes)?.DeclaringType;
        return owner == typeof(object) || owner == typeof(ValueType) ? TypeName(t) : v.ToString() ?? "";
    }

    private static string Failure(Exception e)
    {
        if (e is TargetInvocationException { InnerException: { } inner }) e = inner;
        return $"{{{e.GetType().Name}: {e.Message}}}";
    }

    private static string Escape(string s, char quote)
    {
        var sb = new StringBuilder(s.Length);
        foreach (var c in s)
        {
            if (c == quote || c == '\\') sb.Append('\\').Append(c);
            else if (c == '\n') sb.Append("\\n");
            else if (c == '\r') sb.Append("\\r");
            else if (c == '\t') sb.Append("\\t");
            else if (char.IsControl(c)) sb.Append("\\u").Append(((int)c).ToString("x4"));
            else sb.Append(c);
        }
        return sb.ToString();
    }

    private static bool TryCount(object v, out int count)
    {
        count = 0;
        if (v is ICollection c) { count = c.Count; return true; }
        foreach (var i in v.GetType().GetInterfaces())
        {
            if (!i.IsGenericType) continue;
            var def = i.GetGenericTypeDefinition();
            if (def != typeof(ICollection<>) && def != typeof(IReadOnlyCollection<>)) continue;
            count = (int)i.GetProperty("Count")!.GetValue(v)!;
            return true;
        }
        return false;
    }

    // ponytail: framework collections show only their items (not Capacity, Length, ...); namespace check is a heuristic
    private static bool IsFrameworkCollection(Type t) => t.IsArray || t.Namespace?.StartsWith("System", StringComparison.Ordinal) == true;

    private static IEnumerable<MemberInfo> Members(Type t) =>
        t.GetFields(BindingFlags.Public | BindingFlags.Instance).Cast<MemberInfo>()
            .Concat(t.GetProperties(BindingFlags.Public | BindingFlags.Instance)
                .Where(p => p.GetMethod != null && p.GetIndexParameters().Length == 0));

    /// <summary>True when expanding <paramref name="v"/> would show something.</summary>
    public static bool HasChildren(object? v)
    {
        if (v is null or string or ValueError or Note or Type) return false;
        var t = v.GetType();
        if (t.IsPrimitive || t.IsEnum || v is decimal or DateTime or DateTimeOffset or TimeSpan or Guid or DateOnly or TimeOnly) return false;
        try
        {
            if (v is IEnumerable)
                return !(IsFrameworkCollection(t) && TryCount(v, out var n) && n == 0);
            return Members(t).Any();
        }
        catch (Exception) { return true; }
    }

    public static IEnumerable<(string Name, object? Value, Type Type)> Children(object v)
    {
        var t = v.GetType();
        var result = new List<(string, object?, Type)>();
        var isItems = v is IEnumerable && v is not string;
        if (!(isItems && IsFrameworkCollection(t)))
        {
            foreach (var m in Members(t))
            {
                // Reading these would block forever (Task.Result) or run user code (Lazy.Value); show a note instead.
                if (m.Name == "Result" && v is Task { IsCompleted: false })
                {
                    result.Add((m.Name, new Note("not completed"), typeof(Note)));
                    continue;
                }
                if (m.Name == "Value" && t.IsGenericType && t.GetGenericTypeDefinition() == typeof(Lazy<>)
                    && !(bool)t.GetProperty("IsValueCreated")!.GetValue(v)!)
                {
                    result.Add((m.Name, new Note("not created"), typeof(Note)));
                    continue;
                }
                var (type, get) = m is FieldInfo f ? (f.FieldType, (Func<object?>)(() => f.GetValue(v))) : (((PropertyInfo)m).PropertyType, () => ((PropertyInfo)m).GetValue(v));
                object? value;
                try { value = get(); }
                catch (Exception e) { value = new ValueError(e is TargetInvocationException { InnerException: { } inner } ? inner : e); }
                result.Add((m.Name, value, type));
            }
        }
        if (isItems) AddItems((IEnumerable)v, result);
        return result;
    }

    private static void AddItems(IEnumerable items, List<(string, object?, Type)> result)
    {
        var i = 0;
        IEnumerator? e = null;
        try
        {
            e = items.GetEnumerator();
            while (i < MaxItems && e.MoveNext())
            {
                result.Add(($"[{i}]", e.Current, e.Current?.GetType() ?? typeof(object)));
                i++;
            }
            if (i < MaxItems) return;
            var more = TryCount(items, out var n) ? (n > MaxItems ? $"{n - MaxItems} more" : null) : e.MoveNext() ? "more" : null;
            if (more != null) result.Add(("…", new Note(more), typeof(Note)));
        }
        catch (Exception ex) { result.Add(($"[{i}]", new ValueError(ex), typeof(object))); }
        finally { (e as IDisposable)?.Dispose(); }
    }
}
