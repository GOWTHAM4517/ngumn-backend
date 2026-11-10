package com.ngumn.backend.repository;

import com.ngumn.backend.entity.AuthorityChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuthorityChannelRepository extends JpaRepository<AuthorityChannel, Long> {

    List<AuthorityChannel> findByActiveTrueOrderBySortOrderAscIdAsc();

    List<AuthorityChannel> findAllByOrderBySortOrderAscIdAsc();
}
