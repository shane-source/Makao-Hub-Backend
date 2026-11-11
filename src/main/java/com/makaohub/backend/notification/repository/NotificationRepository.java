package com.makaohub.backend.notification.repository;

import com.makaohub.backend.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository
        extends JpaRepository<Notification, UUID> {

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
        INSERT INTO notifications(id,user_id,booking_id,type,title,body,created_at)
        VALUES (:id,:user,:booking,:type,:title,:body,CURRENT_TIMESTAMP)
        ON CONFLICT (user_id,booking_id,type) WHERE booking_id IS NOT NULL DO NOTHING
        """, nativeQuery = true)
    void insertBookingNotice(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("user") UUID user,
            @org.springframework.data.repository.query.Param("booking") UUID booking,
            @org.springframework.data.repository.query.Param("type") String type,
            @org.springframework.data.repository.query.Param("title") String title,
            @org.springframework.data.repository.query.Param("body") String body);

    List<Notification> findAllByUserIdOrderByCreatedAtDesc(
            UUID userId
    );
}
