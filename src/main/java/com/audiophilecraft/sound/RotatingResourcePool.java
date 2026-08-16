package com.audiophilecraft.sound;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns one shared resource while allowing a clean candidate to atomically
 * replace it. Retired resources are closed only after their final lease ends.
 */
final class RotatingResourcePool<T> implements AutoCloseable {
    private final Supplier<T> factory;
    private final Consumer<T> closer;
    private final AtomicReference<ResourceHandle<T>> current;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RotatingResourcePool(Supplier<T> factory, Consumer<T> closer) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.closer = Objects.requireNonNull(closer, "closer");
        this.current = new AtomicReference<>(new ResourceHandle<>(createResource(), closer));
    }

    Lease<T> acquireShared() {
        while (true) {
            if (closed.get()) throw new IllegalStateException("Resource pool is closed");
            ResourceHandle<T> handle = current.get();
            if (handle == null) throw new IllegalStateException("Resource pool is closed");
            if (handle.tryAcquire()) {
                return new Lease<>(this, handle, null, false);
            }
        }
    }

    Lease<T> acquireCandidate() {
        if (closed.get()) throw new IllegalStateException("Resource pool is closed");
        ResourceHandle<T> expected = current.get();
        if (expected == null) throw new IllegalStateException("Resource pool is closed");

        ResourceHandle<T> candidate = new ResourceHandle<>(createResource(), closer);
        if (!candidate.tryAcquire()) throw new IllegalStateException("Fresh candidate could not be acquired");
        if (closed.get() || current.get() == null) {
            candidate.retire();
            candidate.release();
            throw new IllegalStateException("Resource pool is closed");
        }
        return new Lease<>(this, candidate, expected, true);
    }

    private T createResource() {
        return Objects.requireNonNull(factory.get(), "factory returned null");
    }

    private boolean promote(ResourceHandle<T> candidate, ResourceHandle<T> expected) {
        if (closed.get()) {
            candidate.retire();
            return false;
        }
        if (!current.compareAndSet(expected, candidate)) {
            candidate.retire();
            return false;
        }
        expected.retire();
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ResourceHandle<T> handle = current.getAndSet(null);
        if (handle != null) handle.retire();
    }

    static final class Lease<T> implements AutoCloseable {
        private final RotatingResourcePool<T> owner;
        private final ResourceHandle<T> handle;
        private final ResourceHandle<T> expected;
        private final boolean candidate;
        private boolean promotionAttempted;
        private boolean promoted;
        private boolean closed;

        private Lease(
                RotatingResourcePool<T> owner,
                ResourceHandle<T> handle,
                ResourceHandle<T> expected,
                boolean candidate) {
            this.owner = owner;
            this.handle = handle;
            this.expected = expected;
            this.candidate = candidate;
        }

        T resource() {
            return handle.resource;
        }

        boolean isCandidate() {
            return candidate;
        }

        synchronized boolean promote() {
            if (!candidate || closed) return false;
            if (promotionAttempted) return promoted;
            promotionAttempted = true;
            promoted = owner.promote(handle, expected);
            return promoted;
        }

        @Override
        public void close() {
            boolean retire;
            synchronized (this) {
                if (closed) return;
                closed = true;
                retire = candidate && !promoted;
            }
            if (retire) handle.retire();
            handle.release();
        }
    }

    private static final class ResourceHandle<T> {
        private final T resource;
        private final Consumer<T> closer;
        private int leases;
        private boolean retired;
        private boolean resourceClosed;

        private ResourceHandle(T resource, Consumer<T> closer) {
            this.resource = resource;
            this.closer = closer;
        }

        private synchronized boolean tryAcquire() {
            if (retired || resourceClosed) return false;
            leases++;
            return true;
        }

        private void retire() {
            T resourceToClose = null;
            synchronized (this) {
                if (retired) return;
                retired = true;
                if (leases == 0 && !resourceClosed) {
                    resourceClosed = true;
                    resourceToClose = resource;
                }
            }
            if (resourceToClose != null) closer.accept(resourceToClose);
        }

        private void release() {
            T resourceToClose = null;
            synchronized (this) {
                if (leases <= 0) throw new IllegalStateException("Resource lease released too many times");
                leases--;
                if (retired && leases == 0 && !resourceClosed) {
                    resourceClosed = true;
                    resourceToClose = resource;
                }
            }
            if (resourceToClose != null) closer.accept(resourceToClose);
        }
    }
}
