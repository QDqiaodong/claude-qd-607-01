package com.idc.center.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.Room;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    boolean existsByCode(String code);
}
