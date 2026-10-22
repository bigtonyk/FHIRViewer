package com.spiraleyes.fhirviewer.service.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;

/**
 * Tests {@link BatchProcessor} sequential processing, per-item outcomes,
 * error policies, and cancellation without starting the JavaFX toolkit.
 */
class BatchProcessorTest {

    private static IBaseResource patient(String id) {
        Patient p = new Patient();
        p.setId(id);
        return p;
    }

    private List<IBaseResource> items(int count) {
        List<IBaseResource> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(patient("id-" + i));
        }
        return out;
    }

    @Test
    void allItemsProcessedSuccessfully() {
        BatchProcessor processor = new BatchProcessor();
        List<IBaseResource> items = items(3);
        BatchResult result = processor.process(items, r -> {
            Patient p = (Patient) r;
            p.setActive(true);
            return r;
        });

        assertEquals(3, result.total());
        assertEquals(3, result.processed());
        assertEquals(0, result.failed());
        assertEquals(0, result.skipped());
        assertTrue(result.allProcessed());
        assertFalse(result.hasFailures());
        assertEquals(3, result.items().size());
        for (BatchItemResult item : result.items()) {
            assertTrue(item.processed());
            assertTrue(item.message().isEmpty());
            assertTrue(item.output() instanceof Patient);
            assertTrue(((Patient) item.output()).getActive());
        }
    }

    @Test
    void continuePolicyRecordsAllOutcomes() {
        BatchProcessor processor = new BatchProcessor();
        List<IBaseResource> items = items(3);
        BatchResult result = processor.process(items, r -> {
            Patient p = (Patient) r;
            if (p.getId().equals("id-1")) {
                throw new IllegalStateException("boom");
            }
            return r;
        }, BatchErrorPolicy.CONTINUE);

        assertEquals(3, result.total());
        assertEquals(2, result.processed());
        assertEquals(1, result.failed());
        assertEquals(0, result.skipped());
        assertFalse(result.allProcessed());
        assertTrue(result.hasFailures());

        BatchItemResult failed = result.items().get(1);
        assertEquals(1, failed.index());
        assertTrue(failed.failed());
        assertTrue(failed.message().contains("boom"));
    }

    @Test
    void stopPolicyHaltsOnFirstFailure() {
        BatchProcessor processor = new BatchProcessor();
        List<IBaseResource> items = items(3);
        BatchResult result = processor.process(items, r -> {
            Patient p = (Patient) r;
            if (p.getId().equals("id-0")) {
                throw new IllegalStateException("boom");
            }
            return r;
        }, BatchErrorPolicy.STOP_ON_FIRST_ERROR);

        assertEquals(3, result.total());
        assertEquals(0, result.processed());
        assertEquals(1, result.failed());
        assertEquals(2, result.skipped());
        assertFalse(result.allProcessed());
        assertTrue(result.hasFailures());

        for (int i = 1; i < 3; i++) {
            assertTrue(result.items().get(i).skipped());
        }
    }

    @Test
    void emptyListProducesEmptyResult() {
        BatchProcessor processor = new BatchProcessor();
        BatchResult result = processor.process(List.of(), r -> r);

        assertEquals(0, result.total());
        assertEquals(0, result.processed());
        assertEquals(0, result.failed());
        assertEquals(0, result.skipped());
        assertTrue(result.allProcessed());
    }

    @Test
    void cancellationSkipsRemainingItems() {
        BatchProcessor processor = new BatchProcessor();
        List<IBaseResource> items = items(5);
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        BatchResult result = processor.process(items, r -> {
            if (count.getAndIncrement() == 0) {
                processor.cancel(); // cancel after first item completes
            }
            return r;
        });

        assertEquals(5, result.total());
        assertEquals(1, result.processed());
        assertEquals(0, result.failed());
        assertEquals(4, result.skipped());
        assertTrue(result.cancelled());
    }

    @Test
    void resetCancellationAllowsReuse() {
        BatchProcessor processor = new BatchProcessor();
        List<IBaseResource> items = items(2);

        processor.cancel();
        assertTrue(processor.isCancelled());

        processor.resetCancellation();
        assertFalse(processor.isCancelled());

        BatchResult result = processor.process(items, r -> r);
        assertEquals(2, result.processed());
    }

    @Test
    void defaultPolicyIsContinue() {
        BatchProcessor processor = new BatchProcessor();
        List<IBaseResource> items = items(3);
        BatchResult result = processor.process(items, r -> {
            Patient p = (Patient) r;
            if (p.getId().equals("id-1")) {
                throw new IllegalStateException("mid");
            }
            return r;
        });

        assertEquals(2, result.processed());
        assertEquals(1, result.failed());
        assertEquals(0, result.skipped());
    }

    @Test
    void itemDurationIsRecorded() {
        BatchProcessor processor = new BatchProcessor();
        List<IBaseResource> items = items(1);
        BatchResult result = processor.process(items, r -> r);

        assertEquals(1, result.items().size());
        BatchItemResult item = result.items().get(0);
        assertTrue(item.duration().toNanos() >= 0);
        assertNotNull(item.startedAt());
    }
}