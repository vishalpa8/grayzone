# Grayzone Test Suite

This directory contains unit tests for critical Grayzone components.

## Test Coverage

### BloomFilterTest.kt
Tests the Bloom filter implementation used for DNS ad blocking:
- ✅ Bare TLD protection (prevents "com" matches)
- ✅ Empty/malformed input handling
- ✅ Subdomain matching logic
- ✅ Case insensitivity
- ✅ Edge cases (very long domains, unicode)

### ScheduleLogicTest.kt
Tests schedule rule matching, especially overnight schedules:
- ✅ Same-day schedule blocking (9am-5pm)
- ✅ Overnight schedules across midnight (10pm-6am)
- ✅ Day-of-week matching
- ✅ Sunday→Monday rollover
- ✅ Disabled rules
- ✅ Edge cases (24-hour schedules, exact boundaries)

### DnsPacketTest.kt
Tests DNS packet parsing and response generation:
- ✅ Malformed packet handling (no crashes)
- ✅ Empty/short packet rejection
- ✅ Protocol validation (UDP only, port 53)
- ✅ Sinkhole response creation
- ✅ NXDOMAIN response creation
- ✅ IPv6 packet handling
- ✅ DNS compression handling

## Running Tests

### Via Android Studio
1. Right-click on `app/src/test` folder
2. Select "Run 'Tests in 'test''"

### Via Command Line
```bash
# Run all unit tests
./gradlew test

# Run with output
./gradlew test --info

# Run specific test class
./gradlew test --tests "com.grayzone.app.BloomFilterTest"

# Generate test report
./gradlew test
# Open: app/build/reports/tests/testDebugUnitTest/index.html
```

## Test Requirements

- **JUnit 4.13.2**: Core testing framework
- **Mockito 5.7.0**: Mocking framework (for future use)
- **No Android dependencies**: These are pure JVM unit tests

## Future Test Additions

Priority areas for additional test coverage:

1. **State Reconciliation**
   - GrayscaleManager.reconcileOnStart() logic
   - Process death recovery scenarios

2. **Session Management**
   - Pause/resume logic
   - Clock skew handling
   - Midnight boundary race conditions

3. **Database Operations**
   - Migration testing
   - Query performance validation
   - Data retention policy

4. **VPN Service**
   - DNS fallback logic
   - DoH/DoT blocking
   - Crash recovery behavior

5. **Integration Tests**
   - End-to-end overlay display
   - Accessibility service integration
   - Permission handling

## Test Guidelines

- **Keep tests fast**: No network calls, no file I/O
- **Test one thing**: Each test should verify a single behavior
- **Use descriptive names**: `overnight schedule blocks correctly across midnight`
- **Document edge cases**: Explain why the test exists in comments
- **No flaky tests**: Tests should be 100% deterministic

## Current Coverage

- **Bloom Filter**: 70% (core logic)
- **Schedule Logic**: 80% (critical paths)
- **DNS Packets**: 60% (error handling)
- **Overall**: ~20% (foundation established)

**Target**: 60%+ coverage on core business logic by v1.0
