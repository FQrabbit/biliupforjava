package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import top.sshh.bililiverecoder.entity.RoomLiveGiftCatalog;
import top.sshh.bililiverecoder.repo.RoomLiveGiftCatalogRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GiftCatalogResultWriterTest {
    @Test
    void unchangedValuesDoNotWriteAndRenameNotifiesBothFallbackNamesBeforeMerge() {
        var repository = mock(RoomLiveGiftCatalogRepository.class);
        var updates = mock(StatsUpdateService.class);
        var factory = new DefaultListableBeanFactory(); factory.registerSingleton("updates", updates);
        var writer = new GiftCatalogResultWriter(repository, factory.getBeanProvider(StatsUpdateService.class));
        var previous = catalog("old", 100L);
        when(repository.findByRoomIdAndGiftId("r", 1)).thenReturn(previous);
        var equal = catalog("old", 100L); equal.setPriceCny(new BigDecimal("0.1000"));
        writer.saveChanged(List.of(equal));
        verify(repository, never()).save(any()); verifyNoInteractions(updates);
        List<String> names = new ArrayList<>();
        doAnswer(i -> {
            Collection<RoomLiveGiftCatalog> changed = i.getArgument(0);
            changed.forEach(c -> names.add(c.getGiftName()));
            return null;
        }).when(updates).priceChanged(anyCollection());
        when(repository.save(any())).thenAnswer(i -> {
            RoomLiveGiftCatalog next = i.getArgument(0);
            org.springframework.beans.BeanUtils.copyProperties(next, previous);
            return next;
        });
        writer.saveChanged(List.of(catalog("new", 200L)));
        assertEquals(List.of("old", "new"), names);
        verify(updates, times(1)).priceChanged(anyCollection());
        verify(repository, times(1)).save(any());
    }

    private RoomLiveGiftCatalog catalog(String name, Long coin) {
        var c = new RoomLiveGiftCatalog(); c.setId(1L); c.setRoomId("r"); c.setGiftId(1);
        c.setGiftName(name); c.setPriceCoin(coin); c.setPriceCny(BigDecimal.valueOf(coin).movePointLeft(3));
        c.setUpdatedAt(LocalDateTime.now()); return c;
    }
}
