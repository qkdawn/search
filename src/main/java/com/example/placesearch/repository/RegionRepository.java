package com.example.placesearch.repository;

import com.example.placesearch.entity.Region;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RegionRepository extends JpaRepository<Region, String> {

    @Query(
            // 使用了mysql原生计算距离函数，
            value = "SELECT r.* FROM regions r " +
                    "WHERE (6371000 * 2 * ATAN2(" +
                    "SQRT(" +
                    "POWER(SIN(RADIANS(r.marlat - :lat) / 2), 2) + " +
                    "COS(RADIANS(:lat)) * COS(RADIANS(r.marlat)) * " +
                    "POWER(SIN(RADIANS(r.marlon - :lon) / 2), 2)" +
                    "), " +
                    "SQRT(1 - (" +
                    "POWER(SIN(RADIANS(r.marlat - :lat) / 2), 2) + " +
                    "COS(RADIANS(:lat)) * COS(RADIANS(r.marlat)) * " +
                    "POWER(SIN(RADIANS(r.marlon - :lon) / 2), 2)" +
                    "))" +
                    ")) <= :radius " +
                    "AND (:year IS NULL OR YEAR(r.timestamp) = :year) " +
                    "AND (:typeCodesEmpty = true OR r.typecode IN (:typeCodes))",
            nativeQuery = true
    )
    List<Region> findAround(
            @Param("lon") double lon,
            @Param("lat") double lat,
            @Param("radius") double radius,
            @Param("year") Integer year,
            @Param("typeCodes") List<String> typeCodes,
            @Param("typeCodesEmpty") boolean typeCodesEmpty,
            Pageable pageable
    );

    @Query("SELECT r FROM Region r " +
            "WHERE r.cityname = :cityname " +
            "AND (:year IS NULL OR YEAR(r.timestamp) = :year) " +
            "AND (:typeCodes IS NULL OR r.typecode IN :typeCodes)")
    List<Region> findByCityAndFilters(
            @Param("cityname") String cityname,
            @Param("year") Integer year,
            @Param("typeCodes") List<String> typeCodes,
            Pageable pageable
    );
}
