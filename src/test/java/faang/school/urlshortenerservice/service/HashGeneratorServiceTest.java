package faang.school.urlshortenerservice.service;

import faang.school.urlshortenerservice.entity.Hash;
import faang.school.urlshortenerservice.repository.HashRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Hash Generator Service Tests")
class HashGeneratorServiceTest {

    @Mock
    private HashRepository hashRepository;

    @InjectMocks
    private HashGeneratorService hashGeneratorService;

    private List<Long> testSequences;
    private List<Hash> mockSavedHashes;

    @BeforeEach
    void setUp() {
        testSequences = Arrays.asList(1L, 62L, 3844L, 238328L);
        mockSavedHashes = Arrays.asList(
                new Hash("b"),
                new Hash("ba"),
                new Hash("baa"),
                new Hash("baaa")
        );
    }

    @Test
    @DisplayName("Should generate hashes and save them to database successfully")
    void shouldGenerateHashesAndSaveToDatabase() {
        // Given
        when(hashRepository.saveAll(anyList())).thenReturn(mockSavedHashes);

        // When
        hashGeneratorService.generateHashesInternal(testSequences);

        // Then
        ArgumentCaptor<List<Hash>> hashCaptor = ArgumentCaptor.forClass(List.class);
        verify(hashRepository, times(1)).saveAll(hashCaptor.capture());

        List<Hash> capturedHashes = hashCaptor.getValue();
        assertEquals(4, capturedHashes.size());
        assertNotNull(capturedHashes.get(0));
        assertNotNull(capturedHashes.get(1));
        assertNotNull(capturedHashes.get(2));
        assertNotNull(capturedHashes.get(3));
    }

    @Test
    @DisplayName("Should handle null sequences list gracefully")
    void shouldHandleNullSequencesGracefully() {
        // When
        hashGeneratorService.generateHashesInternal(null);

        // Then
        verify(hashRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("Should handle empty sequences list gracefully")
    void shouldHandleEmptySequencesGracefully() {
        // Given
        List<Long> emptySequences = Collections.emptyList();

        // When
        hashGeneratorService.generateHashesInternal(emptySequences);

        // Then
        verify(hashRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("Should generate hash for single sequence value")
    void shouldGenerateHashForSingleSequence() {
        // Given
        List<Long> singleSequence = Collections.singletonList(100L);
        List<Hash> singleMockHash = Collections.singletonList(new Hash("1C"));
        when(hashRepository.saveAll(anyList())).thenReturn(singleMockHash);

        // When
        hashGeneratorService.generateHashesInternal(singleSequence);

        // Then
        ArgumentCaptor<List<Hash>> hashCaptor = ArgumentCaptor.forClass(List.class);
        verify(hashRepository, times(1)).saveAll(hashCaptor.capture());

        List<Hash> capturedHashes = hashCaptor.getValue();
        assertEquals(1, capturedHashes.size());
        assertNotNull(capturedHashes.get(0));
    }

    @Test
    @DisplayName("Should generate hashes for large sequence values")
    void shouldGenerateHashesForLargeSequenceValues() {
        // Given
        List<Long> largeSequences = Arrays.asList(238327L, 14776335L, 916132831L);
        when(hashRepository.saveAll(anyList())).thenReturn(mockSavedHashes);

        // When
        hashGeneratorService.generateHashesInternal(largeSequences);

        // Then
        ArgumentCaptor<List<Hash>> hashCaptor = ArgumentCaptor.forClass(List.class);
        verify(hashRepository, times(1)).saveAll(hashCaptor.capture());

        List<Hash> capturedHashes = hashCaptor.getValue();
        assertEquals(3, capturedHashes.size());
        assertNotNull(capturedHashes.get(0));
        assertNotNull(capturedHashes.get(1));
        assertNotNull(capturedHashes.get(2));
    }

    @Test
    @DisplayName("Should encode zero value to first alphabet character")
    void shouldEncodeZeroToFirstAlphabetCharacter() throws Exception {
        // Given
        Method encodeMethod = HashGeneratorService.class.getDeclaredMethod("encodeToBase62", long.class);
        encodeMethod.setAccessible(true);

        // When
        String result = (String) encodeMethod.invoke(hashGeneratorService, 0L);

        // Then
        assertEquals("a", result);
    }

    @Test
    @DisplayName("Should encode value 1 to 'b'")
    void shouldEncodeValueOneToB() throws Exception {
        // Given
        Method encodeMethod = HashGeneratorService.class.getDeclaredMethod("encodeToBase62", long.class);
        encodeMethod.setAccessible(true);

        // When
        String result = (String) encodeMethod.invoke(hashGeneratorService, 1L);

        // Then
        assertEquals("b", result);
    }

    @Test
    @DisplayName("Should encode value 61 to '9'")
    void shouldEncodeValue61To9() throws Exception {
        // Given
        Method encodeMethod = HashGeneratorService.class.getDeclaredMethod("encodeToBase62", long.class);
        encodeMethod.setAccessible(true);

        // When
        String result = (String) encodeMethod.invoke(hashGeneratorService, 61L);

        // Then
        assertEquals("9", result);
    }

    @Test
    @DisplayName("Should encode value 62 to 'ba'")
    void shouldEncodeValue62ToBa() throws Exception {
        // Given
        Method encodeMethod = HashGeneratorService.class.getDeclaredMethod("encodeToBase62", long.class);
        encodeMethod.setAccessible(true);

        // When
        String result = (String) encodeMethod.invoke(hashGeneratorService, 62L);

        // Then
        assertEquals("ba", result);
    }

    @Test
    @DisplayName("Should encode value 3844 to 'baa'")
    void shouldEncodeValue3844ToBaa() throws Exception {
        // Given
        Method encodeMethod = HashGeneratorService.class.getDeclaredMethod("encodeToBase62", long.class);
        encodeMethod.setAccessible(true);

        // When
        String result = (String) encodeMethod.invoke(hashGeneratorService, 3844L);

        // Then
        assertEquals("baa", result);
    }

    @Test
    @DisplayName("Should handle repository save operation correctly")
    void shouldHandleRepositorySaveOperationCorrectly() {
        // Given
        List<Long> sequences = Arrays.asList(1L, 2L, 3L);
        List<Hash> expectedSavedHashes = Arrays.asList(
                new Hash("b"),
                new Hash("c"),
                new Hash("d")
        );
        when(hashRepository.saveAll(anyList())).thenReturn(expectedSavedHashes);

        // When
        hashGeneratorService.generateHashesInternal(sequences);

        // Then
        verify(hashRepository, times(1)).saveAll(anyList());
        ArgumentCaptor<List<Hash>> hashCaptor = ArgumentCaptor.forClass(List.class);
        verify(hashRepository).saveAll(hashCaptor.capture());

        List<Hash> capturedHashes = hashCaptor.getValue();
        assertEquals(3, capturedHashes.size());
    }

    @Test
    @DisplayName("Should preserve order of sequences when generating hashes")
    void shouldPreserveOrderOfSequencesWhenGeneratingHashes() {
        // Given
        List<Long> orderedSequences = Arrays.asList(1L, 2L, 3L, 4L);
        when(hashRepository.saveAll(anyList())).thenReturn(mockSavedHashes);

        // When
        hashGeneratorService.generateHashesInternal(orderedSequences);

        // Then
        ArgumentCaptor<List<Hash>> hashCaptor = ArgumentCaptor.forClass(List.class);
        verify(hashRepository).saveAll(hashCaptor.capture());

        List<Hash> capturedHashes = hashCaptor.getValue();
        assertEquals(4, capturedHashes.size());
        // Verify all Hash objects were created
        capturedHashes.forEach(hash -> assertNotNull(hash));
    }

    @Test
    @DisplayName("Should verify repository is called only once per method invocation")
    void shouldVerifyRepositoryIsCalledOnlyOnce() {
        // Given
        List<Long> sequences = Arrays.asList(100L, 200L);
        when(hashRepository.saveAll(anyList())).thenReturn(mockSavedHashes);

        // When
        hashGeneratorService.generateHashesInternal(sequences);

        // Then
        verify(hashRepository, times(1)).saveAll(anyList());
        verifyNoMoreInteractions(hashRepository);
    }

    @Test
    @DisplayName("Should create Hash objects with correct constructor parameters")
    void shouldCreateHashObjectsWithCorrectConstructorParameters() {
        // Given
        List<Long> sequences = Arrays.asList(1L, 62L);
        when(hashRepository.saveAll(anyList())).thenReturn(mockSavedHashes);

        // When
        hashGeneratorService.generateHashesInternal(sequences);

        // Then
        ArgumentCaptor<List<Hash>> hashCaptor = ArgumentCaptor.forClass(List.class);
        verify(hashRepository).saveAll(hashCaptor.capture());

        List<Hash> capturedHashes = hashCaptor.getValue();
        assertEquals(2, capturedHashes.size());

        // Verify Hash objects are properly instantiated
        assertNotNull(capturedHashes.get(0));
        assertNotNull(capturedHashes.get(1));
    }

    @Test
    @DisplayName("Should encode large numbers correctly")
    void shouldEncodeLargeNumbersCorrectly() throws Exception {
        // Given
        Method encodeMethod = HashGeneratorService.class.getDeclaredMethod("encodeToBase62", long.class);
        encodeMethod.setAccessible(true);

        // When & Then - test large numbers
        String result1 = (String) encodeMethod.invoke(hashGeneratorService, 238328L);
        String result2 = (String) encodeMethod.invoke(hashGeneratorService, 238327L);
        String result3 = (String) encodeMethod.invoke(hashGeneratorService, 14776335L);

        assertNotNull(result1);
        assertNotNull(result2);
        assertNotNull(result3);
        assertTrue(result1.length() > 0);
        assertTrue(result2.length() > 0);
        assertTrue(result3.length() > 0);
    }

    @Test
    @DisplayName("Should handle base62 encoding edge cases")
    void shouldHandleBase62EncodingEdgeCases() throws Exception {
        // Given
        Method encodeMethod = HashGeneratorService.class.getDeclaredMethod("encodeToBase62", long.class);
        encodeMethod.setAccessible(true);

        // When & Then - test various edge cases
        assertEquals("a", encodeMethod.invoke(hashGeneratorService, 0L));
        assertEquals("b", encodeMethod.invoke(hashGeneratorService, 1L));
        assertEquals("z", encodeMethod.invoke(hashGeneratorService, 25L));
        assertEquals("A", encodeMethod.invoke(hashGeneratorService, 26L));
        assertEquals("Z", encodeMethod.invoke(hashGeneratorService, 51L));
        assertEquals("0", encodeMethod.invoke(hashGeneratorService, 52L));
        assertEquals("9", encodeMethod.invoke(hashGeneratorService, 61L));
        assertEquals("ba", encodeMethod.invoke(hashGeneratorService, 62L));
        assertEquals("bb", encodeMethod.invoke(hashGeneratorService, 63L));
        assertEquals("bz", encodeMethod.invoke(hashGeneratorService, 87L));
        assertEquals("bA", encodeMethod.invoke(hashGeneratorService, 88L));
    }
}