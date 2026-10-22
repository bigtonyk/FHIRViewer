// Assertion-based test
test.assertTrue(true, "basic");
test.assertEquals(2, 1 + 1);
test.assertFalse(false);
try {
    test.fail("expected failure demo");
} catch (AssertionError e) {
    log.warn("caught: " + e.getMessage());
}
