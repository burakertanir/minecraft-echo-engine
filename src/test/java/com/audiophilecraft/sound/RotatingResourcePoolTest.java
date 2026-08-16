package com.audiophilecraft.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RotatingResourcePoolTest {
    @Test
    void sharedLeasesReuseOneResourceUntilThePoolCloses() {
        FakeFactory factory = new FakeFactory();
        RotatingResourcePool<FakeResource> pool = new RotatingResourcePool<>(factory::create, FakeResource::close);
        RotatingResourcePool.Lease<FakeResource> first = pool.acquireShared();
        RotatingResourcePool.Lease<FakeResource> second = pool.acquireShared();

        assertSame(first.resource(), second.resource());
        first.close();
        second.close();
        assertEquals(0, factory.resource(0).closeCount());

        pool.close();
        assertEquals(1, factory.resource(0).closeCount());
    }

    @Test
    void successfulCandidateBecomesSharedWithoutClosingAnActiveOldResource() {
        FakeFactory factory = new FakeFactory();
        RotatingResourcePool<FakeResource> pool = new RotatingResourcePool<>(factory::create, FakeResource::close);
        RotatingResourcePool.Lease<FakeResource> oldStream = pool.acquireShared();
        RotatingResourcePool.Lease<FakeResource> candidate = pool.acquireCandidate();

        assertNotSame(oldStream.resource(), candidate.resource());
        assertTrue(candidate.promote());
        assertEquals(0, oldStream.resource().closeCount(), "promotion must not cut an active old stream");

        RotatingResourcePool.Lease<FakeResource> nextTrack = pool.acquireShared();
        assertSame(candidate.resource(), nextTrack.resource());

        oldStream.close();
        assertEquals(1, factory.resource(0).closeCount());
        candidate.close();
        nextTrack.close();
        assertEquals(0, factory.resource(1).closeCount());

        pool.close();
        assertEquals(1, factory.resource(1).closeCount());
    }

    @Test
    void unpromotedCandidateStaysPrivateAndClosesAfterItsRequest() {
        FakeFactory factory = new FakeFactory();
        RotatingResourcePool<FakeResource> pool = new RotatingResourcePool<>(factory::create, FakeResource::close);
        RotatingResourcePool.Lease<FakeResource> candidate = pool.acquireCandidate();
        FakeResource candidateResource = candidate.resource();

        candidate.close();

        assertEquals(1, candidateResource.closeCount());
        try (RotatingResourcePool.Lease<FakeResource> shared = pool.acquireShared()) {
            assertSame(factory.resource(0), shared.resource());
        }
        pool.close();
    }

    @Test
    void onlyOneCandidateCanReplaceTheExpectedSharedResource() throws Exception {
        FakeFactory factory = new FakeFactory();
        RotatingResourcePool<FakeResource> pool = new RotatingResourcePool<>(factory::create, FakeResource::close);
        List<RotatingResourcePool.Lease<FakeResource>> candidates = new ArrayList<>();
        for (int index = 0; index < 12; index++) candidates.add(pool.acquireCandidate());

        ExecutorService executor = Executors.newFixedThreadPool(candidates.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> promotions = new ArrayList<>();
        try {
            for (RotatingResourcePool.Lease<FakeResource> candidate : candidates) {
                promotions.add(executor.submit(() -> {
                    start.await();
                    return candidate.promote();
                }));
            }
            start.countDown();

            int winnerIndex = -1;
            int successCount = 0;
            for (int index = 0; index < promotions.size(); index++) {
                if (promotions.get(index).get(5, TimeUnit.SECONDS)) {
                    winnerIndex = index;
                    successCount++;
                }
            }
            assertEquals(1, successCount);

            try (RotatingResourcePool.Lease<FakeResource> shared = pool.acquireShared()) {
                assertSame(candidates.get(winnerIndex).resource(), shared.resource());
            }

            for (RotatingResourcePool.Lease<FakeResource> candidate : candidates) candidate.close();
            for (int index = 0; index < candidates.size(); index++) {
                int expectedCloseCount = index == winnerIndex ? 0 : 1;
                assertEquals(
                        expectedCloseCount, candidates.get(index).resource().closeCount());
            }
        } finally {
            executor.shutdownNow();
            for (RotatingResourcePool.Lease<FakeResource> candidate : candidates) candidate.close();
            pool.close();
        }
    }

    @Test
    void candidateLosesPromotionIfAnotherCandidateAlreadyWon() {
        FakeFactory factory = new FakeFactory();
        RotatingResourcePool<FakeResource> pool = new RotatingResourcePool<>(factory::create, FakeResource::close);
        RotatingResourcePool.Lease<FakeResource> first = pool.acquireCandidate();
        RotatingResourcePool.Lease<FakeResource> second = pool.acquireCandidate();

        assertTrue(first.promote());
        assertFalse(second.promote());
        assertFalse(second.promote(), "a losing candidate must not retry promotion against a newer shared resource");
        second.close();
        assertEquals(1, second.resource().closeCount());

        try (RotatingResourcePool.Lease<FakeResource> shared = pool.acquireShared()) {
            assertSame(first.resource(), shared.resource());
        }
        first.close();
        pool.close();
    }

    @Test
    void promotionIsIdempotentForTheWinnerButUnavailableToSharedLeases() {
        FakeFactory factory = new FakeFactory();
        RotatingResourcePool<FakeResource> pool = new RotatingResourcePool<>(factory::create, FakeResource::close);
        RotatingResourcePool.Lease<FakeResource> shared = pool.acquireShared();
        RotatingResourcePool.Lease<FakeResource> candidate = pool.acquireCandidate();

        assertFalse(shared.promote());
        assertTrue(candidate.promote());
        assertTrue(candidate.promote());

        shared.close();
        candidate.close();
        assertEquals(1, factory.resource(0).closeCount());
        assertEquals(0, factory.resource(1).closeCount());
        pool.close();
        assertEquals(1, factory.resource(1).closeCount());
    }

    @Test
    void cancelledCandidateCannotBePromotedOrPublished() {
        FakeFactory factory = new FakeFactory();
        RotatingResourcePool<FakeResource> pool = new RotatingResourcePool<>(factory::create, FakeResource::close);
        RotatingResourcePool.Lease<FakeResource> candidate = pool.acquireCandidate();

        candidate.close();

        assertFalse(candidate.promote());
        assertEquals(1, candidate.resource().closeCount());
        try (RotatingResourcePool.Lease<FakeResource> shared = pool.acquireShared()) {
            assertSame(factory.resource(0), shared.resource());
        }
        pool.close();
    }

    @Test
    void poolShutdownRejectsCandidatePromotionAndClosesItAfterRelease() {
        FakeFactory factory = new FakeFactory();
        RotatingResourcePool<FakeResource> pool = new RotatingResourcePool<>(factory::create, FakeResource::close);
        RotatingResourcePool.Lease<FakeResource> candidate = pool.acquireCandidate();

        pool.close();

        assertFalse(candidate.promote());
        assertEquals(0, candidate.resource().closeCount());
        candidate.close();
        assertEquals(1, candidate.resource().closeCount());
        assertEquals(1, factory.resource(0).closeCount());
    }

    @Test
    void closingPoolDefersShutdownUntilOutstandingLeaseEndsAndRejectsNewWork() {
        FakeFactory factory = new FakeFactory();
        RotatingResourcePool<FakeResource> pool = new RotatingResourcePool<>(factory::create, FakeResource::close);
        RotatingResourcePool.Lease<FakeResource> active = pool.acquireShared();

        pool.close();

        assertEquals(0, active.resource().closeCount());
        assertThrows(IllegalStateException.class, pool::acquireShared);
        assertThrows(IllegalStateException.class, pool::acquireCandidate);

        active.close();
        active.close();
        assertEquals(1, factory.resource(0).closeCount());
    }

    private static final class FakeFactory {
        private final List<FakeResource> resources = new ArrayList<>();

        private synchronized FakeResource create() {
            FakeResource resource = new FakeResource(resources.size());
            resources.add(resource);
            return resource;
        }

        private synchronized FakeResource resource(int index) {
            return resources.get(index);
        }
    }

    private static final class FakeResource {
        private final int id;
        private final AtomicInteger closeCount = new AtomicInteger();

        private FakeResource(int id) {
            this.id = id;
        }

        private void close() {
            closeCount.incrementAndGet();
        }

        private int closeCount() {
            return closeCount.get();
        }

        @Override
        public String toString() {
            return "FakeResource{" + id + '}';
        }
    }
}
