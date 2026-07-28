package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neobank.module.repository.AccountRecordRepository;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReferenceGeneratorTest {

    private AccountRecordRepository accountRecords;
    private ReferenceGenerator generator;

    @BeforeEach
    void setUp() {
        accountRecords = mock(AccountRecordRepository.class);
        when(accountRecords.findByReference(anyString())).thenReturn(Optional.empty());
        generator = new ReferenceGenerator(accountRecords);
    }

    @Test
    void generatesAReferenceMatchingTheAccPrefixShape() {
        assertThat(generator.next()).matches("acc-[0-9a-f]{8}");
    }

    @Test
    void athousandCallsProduceDistinctValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(generator.next());
        }
        assertThat(seen).hasSize(1000);
    }
}
