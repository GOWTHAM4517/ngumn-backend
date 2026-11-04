package com.ngumn.backend.support;

import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Data repositories kept in memory, for testing the real services
 * without a database. Derived queries (findByXAndYOrderByZDesc,
 * countBy..., existsBy..., Top/First) are read with Spring Data's own
 * PartTree parser, so they behave like the JPA ones; @PrePersist and
 * @PreUpdate run on save as they would in Hibernate. (Hand-written @Query
 * methods aren't supported - none of the flows under test use them.)
 * Safe to use from the services' background threads (contacts are told on
 * one): every list is copy-on-write, so a query never sees half a save.
 */
public final class InMemoryRepositories {

    private final Map<Class<?>, List<Object>> data = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    /** Every saved row of an entity type. */
    @SuppressWarnings("unchecked")
    public <T> List<T> rows(Class<T> type) {
        return (List<T>) Collections.unmodifiableList(data.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()));
    }

    @SuppressWarnings("unchecked")
    public <R> R of(Class<R> repositoryInterface) {
        Class<?> domain = null;
        for (Type t : repositoryInterface.getGenericInterfaces()) {
            if (t instanceof ParameterizedType p) domain = (Class<?>) p.getActualTypeArguments()[0];
        }
        if (domain == null) throw new IllegalArgumentException(repositoryInterface + " has no entity type");
        final Class<?> dom = domain;
        return (R) Proxy.newProxyInstance(repositoryInterface.getClassLoader(), new Class<?>[]{repositoryInterface},
                (proxy, m, args) -> invoke(proxy, m, args, dom, repositoryInterface));
    }

    private Object invoke(Object proxy, Method m, Object[] args, Class<?> dom, Class<?> iface) throws Throwable {
        if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
        List<Object> rows = data.computeIfAbsent(dom, k -> new CopyOnWriteArrayList<>());
        int argc = args == null ? 0 : args.length;
        switch (m.getName()) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == args[0];
            case "toString": return iface.getSimpleName();
            case "save": case "saveAndFlush": return save(rows, args[0]);
            case "saveAll": case "saveAllAndFlush": {
                List<Object> out = new ArrayList<>();
                for (Object o : (Iterable<?>) args[0]) out.add(save(rows, o));
                return out;
            }
            case "findById": return Optional.ofNullable(rows.stream().filter(r -> same(idOf(r), args[0])).findFirst().orElse(null));
            case "existsById": return rows.stream().anyMatch(r -> same(idOf(r), args[0]));
            case "findAllById": {
                List<Object> wanted = list((Iterable<?>) args[0]);
                return new ArrayList<>(rows.stream().filter(r -> wanted.stream().anyMatch(w -> same(idOf(r), w))).toList());
            }
            case "delete": rows.removeIf(r -> r == args[0]); return null;
            case "deleteById": rows.removeIf(r -> same(idOf(r), args[0])); return null;
            case "deleteAllById": {
                List<Object> gone = list((Iterable<?>) args[0]);
                rows.removeIf(r -> gone.stream().anyMatch(g -> same(idOf(r), g)));
                return null;
            }
            case "deleteAll": case "deleteAllInBatch": case "deleteInBatch":
                if (argc == 0) { rows.clear(); return null; }
                { List<Object> gone = list((Iterable<?>) args[0]); rows.removeIf(r -> gone.stream().anyMatch(g -> g == r)); return null; }
            case "flush": return null;
            case "count": if (argc == 0) return (long) rows.size(); break;
            case "findAll": if (argc == 0) return new ArrayList<>(rows); break;
            default: break;
        }
        return derived(m, args, rows, dom);
    }

    private Object derived(Method m, Object[] args, List<Object> rows, Class<?> dom) {
        PartTree tree = new PartTree(m.getName(), dom);
        List<Object> bound = new ArrayList<>();
        Sort extra = Sort.unsorted();
        if (args != null) for (Object a : args) { if (a instanceof Sort s) extra = s; else bound.add(a); }
        boolean hasParts = tree.getParts().iterator().hasNext();
        List<Object> out = new ArrayList<>();
        for (Object row : rows) {
            Iterator<Object> it = bound.iterator();
            boolean any = false;
            // Arguments bind in order across all parts, so every part is read even after a match.
            for (PartTree.OrPart or : tree) {
                boolean all = true;
                for (Part p : or) if (!matches(p, row, it)) all = false;
                if (all) any = true;
            }
            if (!hasParts || any) out.add(row);
        }
        Sort sort = tree.getSort().and(extra);
        if (sort.isSorted()) {
            Comparator<Object> c = null;
            for (Sort.Order o : sort) {
                Comparator<Object> one = (a, b) -> compare(property(a, o.getProperty()), property(b, o.getProperty()));
                if (o.isDescending()) one = one.reversed();
                c = c == null ? one : c.thenComparing(one);
            }
            out.sort(c);
        }
        if (tree.isLimiting()) out = new ArrayList<>(out.subList(0, Math.min(out.size(), tree.getMaxResults())));
        Class<?> rt = m.getReturnType();
        if (tree.isDelete()) {
            List<Object> gone = out;
            rows.removeIf(r -> gone.stream().anyMatch(g -> g == r));
            return rt == void.class ? null : (Object) (long) gone.size();
        }
        if (tree.isCountProjection() || rt == long.class || rt == Long.class) return (long) out.size();
        if (tree.isExistsProjection() || rt == boolean.class || rt == Boolean.class) return !out.isEmpty();
        if (Collection.class.isAssignableFrom(rt)) return out;
        if (rt == Optional.class) return Optional.ofNullable(out.isEmpty() ? null : out.get(0));
        return out.isEmpty() ? null : out.get(0);
    }

    private static boolean matches(Part part, Object row, Iterator<Object> args) {
        Object v = property(row, part.getProperty().toDotPath());
        boolean ignoreCase = part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER;
        switch (part.getType()) {
            case SIMPLE_PROPERTY: {
                Object a = args.next();
                return ignoreCase && v instanceof String s && a instanceof String t ? s.equalsIgnoreCase(t) : same(v, a);
            }
            case NEGATING_SIMPLE_PROPERTY: return !same(v, args.next());
            case AFTER: case GREATER_THAN: return v != null && compare(v, args.next()) > 0;
            case GREATER_THAN_EQUAL: return v != null && compare(v, args.next()) >= 0;
            case BEFORE: case LESS_THAN: return v != null && compare(v, args.next()) < 0;
            case LESS_THAN_EQUAL: return v != null && compare(v, args.next()) <= 0;
            case BETWEEN: { Object lo = args.next(), hi = args.next(); return v != null && compare(v, lo) >= 0 && compare(v, hi) <= 0; }
            case IS_NULL: return v == null;
            case IS_NOT_NULL: return v != null;
            case TRUE: return Boolean.TRUE.equals(v);
            case FALSE: return Boolean.FALSE.equals(v);
            case IN: { Collection<?> c = (Collection<?>) args.next(); return c.stream().anyMatch(x -> same(v, x)); }
            case NOT_IN: { Collection<?> c = (Collection<?>) args.next(); return c.stream().noneMatch(x -> same(v, x)); }
            case CONTAINING: {
                String a = (String) args.next();
                return v != null && (ignoreCase ? v.toString().toLowerCase().contains(a.toLowerCase()) : v.toString().contains(a));
            }
            case STARTING_WITH: { String a = (String) args.next(); return v != null && v.toString().startsWith(a); }
            default: throw new UnsupportedOperationException("Query keyword not supported here: " + part.getType());
        }
    }

    private synchronized Object save(List<Object> rows, Object o) throws Exception {
        Field id = idField(o.getClass());
        if (id.get(o) == null) {
            long next = ids.incrementAndGet();
            id.set(o, id.getType() == Integer.class ? (Object) (int) next : (Object) next);
            lifecycle(o, jakarta.persistence.PrePersist.class);
        } else {
            lifecycle(o, jakarta.persistence.PreUpdate.class);
        }
        if (rows.stream().noneMatch(r -> r == o)) rows.add(o);
        return o;
    }

    private static List<Object> list(Iterable<?> it) {
        List<Object> out = new ArrayList<>();
        for (Object o : it) out.add(o);
        return out;
    }

    private static Field idField(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new IllegalStateException("No @Id on " + c.getSimpleName());
    }

    private static Object idOf(Object o) {
        try {
            return idField(o.getClass()).get(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void lifecycle(Object o, Class<? extends Annotation> a) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.isAnnotationPresent(a)) {
                    m.setAccessible(true);
                    m.invoke(o);
                }
            }
        }
    }

    /** "reporter.id" -> the value, following the object graph. */
    static Object property(Object o, String dotPath) {
        Object cur = o;
        for (String p : dotPath.split("\\.")) {
            if (cur == null) return null;
            Field f = null;
            for (Class<?> k = cur.getClass(); k != null && f == null; k = k.getSuperclass()) {
                try { f = k.getDeclaredField(p); } catch (NoSuchFieldException ignored) { }
            }
            if (f == null) throw new IllegalStateException("No property " + p + " on " + cur.getClass().getSimpleName());
            f.setAccessible(true);
            try { cur = f.get(cur); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        return cur;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        return Objects.equals(a, b);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return ((Comparable) a).compareTo(b);
    }
}
