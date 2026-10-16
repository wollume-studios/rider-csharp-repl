using System.Collections;
using System.Globalization;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Text;

namespace CSharpRepl.Host;

/// <summary>Turns a live value into a C# expression that rebuilds it, or reports that it can't.</summary>
public static class ValueToCode
{
    private const int MaxDepth = 5;

    // Namespaces needing no qualification in the REPL's imports and in a file-based app's implicit usings.
    private static readonly HashSet<string> Imported =
        ["System", "System.Collections.Generic", "System.IO", "System.Linq", "System.Threading.Tasks"];

    /// <param name="declared">The variable's declared type: a different runtime type gets a cast, null gets a typed null.</param>
    /// <param name="replTypes">False when the code will live outside the session, where types declared in the REPL don't exist.</param>
    public static bool TryToCode(object? v, Type declared, out string code, bool replTypes = true)
    {
        try
        {
            var e = new Emitter(replTypes);
            if (v is null) code = $"({e.TypeName(declared)}{(declared.IsValueType ? "" : "?")})null";
            else
            {
                code = e.Value(v, 0);
                if (declared != v.GetType()) code = $"({e.TypeName(declared)}){(code.StartsWith('-') ? $"({code})" : code)}";
            }
            return true;
        }
        catch (Exception) // unsupported shape, cycle, too deep, or a getter that threw
        {
            code = "";
            return false;
        }
    }

    private sealed class Emitter(bool replTypes)
    {
        private readonly HashSet<object> _path = new(ReferenceEqualityComparer.Instance);

        public string Value(object? v, int depth)
        {
            if (v is null) return "null";
            if (depth > MaxDepth) throw new NotSupportedException("too deep");
            if (Scalar(v) is { } scalar) return scalar;
            var t = v.GetType();
            if (t.IsValueType) return Composite(v, t, depth);
            if (!_path.Add(v)) throw new NotSupportedException("cycle");
            try { return Composite(v, t, depth); }
            finally { _path.Remove(v); }
        }

        private string? Scalar(object v) => v switch
        {
            bool b => b ? "true" : "false",
            int i => i == int.MinValue ? "int.MinValue" : Inv(i),
            long l => l == long.MinValue ? "long.MinValue" : Inv(l) + "L",
            uint u => Inv(u) + "u",
            ulong u => Inv(u) + "UL",
            short or ushort or byte or sbyte => $"({ValueFormatter.TypeName(v.GetType())}){Inv(v)}",
            double d => Real(d, "double", d.ToString("R", CultureInfo.InvariantCulture), ""),
            float f => Real(f, "float", f.ToString("R", CultureInfo.InvariantCulture), "f"),
            decimal m => Inv(m) + "m",
            char c => "'" + Escape(c.ToString(), '\'') + "'",
            string s => "\"" + Escape(s, '"') + "\"",
            DateTime d => DateTimeCode(d),
            DateTimeOffset o => $"new DateTimeOffset({o.Ticks}L, new TimeSpan({o.Offset.Ticks}L))",
            TimeSpan ts => $"new TimeSpan({ts.Ticks}L)",
            DateOnly d => $"new DateOnly({d.Year}, {d.Month}, {d.Day})",
            TimeOnly t => $"new TimeOnly({t.Ticks}L)",
            Guid g => $"new Guid(\"{g}\")",
            Enum e => EnumCode(e),
            _ => null,
        };

        private static string Inv(object v) => Convert.ToString(v, CultureInfo.InvariantCulture)!;

        private static string Real(double d, string type, string r, string suffix)
        {
            if (double.IsNaN(d)) return type + ".NaN";
            if (double.IsInfinity(d)) return type + (d > 0 ? ".PositiveInfinity" : ".NegativeInfinity");
            return suffix == "" && !r.Contains('.') && !r.Contains('E') ? r + ".0" : r + suffix;
        }

        private static string DateTimeCode(DateTime d)
        {
            var code = $"new DateTime({d.Year}, {d.Month}, {d.Day}, {d.Hour}, {d.Minute}, {d.Second}, {d.Millisecond}, DateTimeKind.{d.Kind})";
            var subMs = d.Ticks % TimeSpan.TicksPerMillisecond;
            return subMs == 0 ? code : $"{code}.AddTicks({subMs}L)";
        }

        private string EnumCode(Enum e)
        {
            var t = e.GetType();
            var name = TypeName(t);
            if (Enum.IsDefined(t, e)) return $"{name}.{e}";
            var s = e.ToString();
            if (t.IsDefined(typeof(FlagsAttribute), false) && !char.IsDigit(s[0]) && s[0] != '-')
                return "(" + string.Join(" | ", s.Split(", ").Select(n => $"{name}.{n}")) + ")";
            return $"({name})({Convert.ToDecimal(e, CultureInfo.InvariantCulture).ToString(CultureInfo.InvariantCulture)})";
        }

        private static string Escape(string s, char quote)
        {
            var sb = new StringBuilder(s.Length);
            for (var i = 0; i < s.Length; i++)
            {
                var c = s[i];
                if (char.IsHighSurrogate(c) && i + 1 < s.Length && char.IsLowSurrogate(s[i + 1])) sb.Append(c).Append(s[++i]);
                else if (c == quote || c == '\\') sb.Append('\\').Append(c);
                else if (c == '\0') sb.Append("\\0");
                else if (c == '\n') sb.Append("\\n");
                else if (c == '\r') sb.Append("\\r");
                else if (c == '\t') sb.Append("\\t");
                else if (char.GetUnicodeCategory(c) is UnicodeCategory.Control or UnicodeCategory.Format or UnicodeCategory.LineSeparator
                         or UnicodeCategory.ParagraphSeparator or UnicodeCategory.Surrogate or UnicodeCategory.PrivateUse or UnicodeCategory.OtherNotAssigned)
                    sb.Append("\\u").Append(((int)c).ToString("x4"));
                else sb.Append(c);
            }
            return sb.ToString();
        }

        private string Composite(object v, Type t, int depth)
        {
            if (t.IsArray)
            {
                if (t.GetArrayRank() != 1) throw new NotSupportedException("multi-dimensional array");
                return Initialized(TypeName(t), (IEnumerable)v, depth);
            }
            if (t.IsGenericType && t.FullName!.StartsWith("System.ValueTuple`", StringComparison.Ordinal))
            {
                var items = (ITuple)v;
                if (items.Length is < 2 or > 7) throw new NotSupportedException("tuple arity");
                return $"new {TypeName(t)}({string.Join(", ", Enumerable.Range(0, items.Length).Select(i => Value(items[i], depth + 1)))})";
            }
            if (t.IsGenericType && t.GetGenericTypeDefinition() == typeof(KeyValuePair<,>))
                return $"new {TypeName(t)}({Value(t.GetProperty("Key")!.GetValue(v), depth + 1)}, {Value(t.GetProperty("Value")!.GetValue(v), depth + 1)})";
            if (v is IDictionary dict)
            {
                RequireMutableWithDefaultCtor(t, dict.IsReadOnly || dict.IsFixedSize);
                var entries = new List<string>();
                for (var e = dict.GetEnumerator(); e.MoveNext();) entries.Add($"[{Value(e.Key, depth + 1)}] = {Value(e.Value, depth + 1)}");
                return entries.Count == 0 ? $"new {TypeName(t)}()" : $"new {TypeName(t)} {{ {string.Join(", ", entries)} }}";
            }
            if (t.GetInterfaces().FirstOrDefault(i => i.IsGenericType && i.GetGenericTypeDefinition() == typeof(ICollection<>)) is { } collection)
            {
                RequireMutableWithDefaultCtor(t, (bool)collection.GetProperty("IsReadOnly")!.GetValue(v)!);
                return Initialized(TypeName(t), (IEnumerable)v, depth);
            }
            if (v is IEnumerable) throw new NotSupportedException("unsupported collection");
            return Object(v, t, depth);
        }

        private static void RequireMutableWithDefaultCtor(Type t, bool readOnly)
        {
            if (readOnly || t.GetConstructor(Type.EmptyTypes) == null) throw new NotSupportedException("can't be rebuilt by adding items");
        }

        private string Initialized(string type, IEnumerable items, int depth)
        {
            var parts = items.Cast<object?>().Select(i => Value(i, depth + 1)).ToArray();
            return parts.Length == 0 ? (type.EndsWith(']') ? $"new {type} {{ }}" : $"new {type}()") : $"new {type} {{ {string.Join(", ", parts)} }}";
        }

        private sealed record Member(string Name, Type Type, Func<object, object?> Get, bool Settable);

        private static IEnumerable<Member> Members(Type t) =>
            t.GetFields(BindingFlags.Public | BindingFlags.Instance).Select(f => new Member(f.Name, f.FieldType, f.GetValue, !f.IsInitOnly))
                .Concat(t.GetProperties(BindingFlags.Public | BindingFlags.Instance)
                    .Where(p => p.GetMethod?.IsPublic == true && p.GetIndexParameters().Length == 0)
                    .Select(p => new Member(p.Name, p.PropertyType, p.GetValue, p.SetMethod?.IsPublic == true)));

        // ponytail: state that isn't a public property/field or ctor argument (private setters, extra collection items) is not captured
        private string Object(object v, Type t, int depth)
        {
            var members = Members(t).ToArray();
            if (t.IsDefined(typeof(CompilerGeneratedAttribute), false) && t.Name.Contains("AnonymousType"))
                return $"new {{ {string.Join(", ", members.Select(m => $"{m.Name} = {Value(m.Get(v), depth + 1)}"))} }}";

            // Only user types: framework objects (StringBuilder, Stream, Task, ...) hold state this can't see.
            if (t.Namespace is { } ns && (ns == "System" || ns.StartsWith("System.") || ns.StartsWith("Microsoft."))) throw new NotSupportedException("framework type");

            // The constructor with the most parameters that all map to a member of the same name (a positional record's), else the default one.
            var ctor = t.GetConstructors()
                .Select(c => c.GetParameters())
                .Where(ps => !(ps.Length == 1 && ps[0].ParameterType == t))
                .Where(ps => ps.All(p => members.Any(m => Same(m.Name, p.Name) && p.ParameterType.IsAssignableFrom(m.Type))))
                .OrderByDescending(ps => ps.Length)
                .FirstOrDefault();
            if (ctor == null && !t.IsValueType) throw new NotSupportedException("no usable constructor");

            ctor ??= [];
            var args = ctor.Select(p => Value(members.First(m => Same(m.Name, p.Name)).Get(v), depth + 1)).ToArray();
            var init = members.Where(m => m.Settable && !ctor.Any(p => Same(m.Name, p.Name)))
                .Select(m => $"{m.Name} = {Value(m.Get(v), depth + 1)}").ToArray();
            var code = "new " + TypeName(t);
            if (args.Length > 0 || init.Length == 0) code += $"({string.Join(", ", args)})";
            return init.Length == 0 ? code : $"{code} {{ {string.Join(", ", init)} }}";
        }

        private static bool Same(string a, string? b) => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

        /// <summary>The C# name of a type as it would be written in code; throws for types that can't be named.</summary>
        public string TypeName(Type t)
        {
            if (t == typeof(void) || t.IsPointer || t.IsByRef || t.IsGenericParameter) throw new NotSupportedException("unnameable type");
            if (t.IsPrimitive || t == typeof(string) || t == typeof(object) || t == typeof(decimal)) return ValueFormatter.TypeName(t);
            if (t.IsArray) return TypeName(t.GetElementType()!) + "[" + new string(',', t.GetArrayRank() - 1) + "]";
            if (!t.IsVisible || t.Name.Contains('<')) throw new NotSupportedException("type isn't nameable: " + t);

            var all = t.IsGenericType ? t.GetGenericArguments() : [];
            if (t.IsGenericType && t.GetGenericTypeDefinition() == typeof(Nullable<>)) return TypeName(all[0]) + "?";
            var own = all;
            string prefix;
            if (t.DeclaringType is { } outer)
            {
                var n = outer.IsGenericTypeDefinition ? outer.GetGenericArguments().Length : 0;
                if (n > 0) { outer = outer.MakeGenericType(all[..n]); own = all[n..]; }
                if (outer.DeclaringType == null && outer.Name.StartsWith("Submission#", StringComparison.Ordinal))
                {
                    if (!replTypes) throw new NotSupportedException("type declared in the REPL");
                    prefix = "";
                }
                else prefix = TypeName(outer) + ".";
            }
            else prefix = t.Namespace == null || Imported.Contains(t.Namespace) ? "" : t.Namespace + ".";

            var name = t.Name.Contains('`') ? t.Name[..t.Name.IndexOf('`')] : t.Name;
            return prefix + name + (own.Length > 0 ? "<" + string.Join(", ", own.Select(TypeName)) + ">" : "");
        }
    }
}
