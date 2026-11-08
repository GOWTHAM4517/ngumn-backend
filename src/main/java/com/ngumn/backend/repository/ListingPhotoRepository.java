package com.ngumn.backend.repository;

import com.ngumn.backend.entity.ListingPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ListingPhotoRepository extends JpaRepository<ListingPhoto, Long> {
    Optional<ListingPhoto> findByToken(String token);

    long countByListingId(Long listingId);
}
