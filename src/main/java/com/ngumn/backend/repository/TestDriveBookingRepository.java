package com.ngumn.backend.repository;

import com.ngumn.backend.entity.TestDriveBooking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TestDriveBookingRepository extends JpaRepository<TestDriveBooking, Long> {
    List<TestDriveBooking> findByStatusIn(Collection<String> statuses);

    List<TestDriveBooking> findByListingIdOrderByMeetAtDesc(Long listingId);

    List<TestDriveBooking> findByCarIdAndStatus(Long carId, String status);

    List<TestDriveBooking> findTop20ByCarIdAndSellerIdOrderByMeetAtDesc(Long carId, Long sellerId);

    List<TestDriveBooking> findByBuyerIdOrderByMeetAtDesc(Long buyerId);

    List<TestDriveBooking> findBySellerIdOrderByMeetAtDesc(Long sellerId);

    List<TestDriveBooking> findBySellerIdAndStatus(Long sellerId, String status);

    List<TestDriveBooking> findByBuyerIdAndStatus(Long buyerId, String status);
}
