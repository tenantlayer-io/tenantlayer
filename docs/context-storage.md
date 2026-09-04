# Context storage

## Where the tenant is kept

`TenantContext` delegates to a `TenantContextStorage`. The shipped implementation is a
plain `ThreadLocal`.

```java
public interface TenantContextStorage {
    TenantScope get();
    void set(TenantScope scope);
    void clear();
}
```

## Why it is an interface with one implementation

Every propagation adapter in the library — the servlet filter, the task decorator, the
executor wrapper, the Kafka interceptors, the scheduler helper — reads and writes the
context. If the storage mechanism changes later and those call sites each reach for their
own `ThreadLocal`, every one of them is a separate migration and a separate chance to get
it wrong. They all go through `TenantContext`, and `TenantContext` goes through this.

## Why not `ScopedValue` yet

`ScopedValue` is the better answer: immutable for the duration of a binding, inherited by
structured-concurrency forks automatically, and impossible to leave behind on a pooled
thread because there is no setter to forget to unset.

It is a **preview API on JDK 21 through 24** and final only in JDK 25. Shipping an
implementation now would force `--enable-preview` on every consumer, and TenantLayer's
baseline is Java 17 so that Spring Boot 3.x applications can adopt it. The seam is defined;
the implementation lands when the baseline reaches a JDK where the API is final.

## Why not `InheritableThreadLocal`

It sounds like exactly what a propagation library wants, and it is a trap. It copies the
value at thread *creation*, which for a pooled executor is whenever the pool happened to
grow. A worker created while serving acme keeps acme as its inherited default forever, and
every later task that fails to set a tenant runs as acme instead of failing closed.

Propagation is explicit instead: decorators capture at submit time and restore afterwards.

## Substituting your own

```java
TenantContext.useStorage(new MyStorage());
```

Call it once during start-up, before any tenant is bound. An implementation must be safe
for concurrent use and must never let one thread observe another's tenant. Returning `null`
from `get()` means "no tenant", which callers on the enforcement path treat as fail-closed.
