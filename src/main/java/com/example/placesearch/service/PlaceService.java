package com.example.placesearch.service;

import com.example.placesearch.dto.request.AroundSearchRequest;
import com.example.placesearch.dto.request.CitySearchRequest;
import com.example.placesearch.dto.response.PoiResponse;
import com.example.placesearch.dto.response.SearchResponse;
import com.example.placesearch.entity.Code;
import com.example.placesearch.entity.Region;
import com.example.placesearch.repository.CodeRepository;
import com.example.placesearch.repository.RegionRepository;
import com.example.placesearch.util.LocationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlaceService {
    private final RegionRepository regionRepository;
    private final CodeRepository codeRepository;

    public SearchResponse searchAround(AroundSearchRequest request) {
        log.info("===== 开始处理搜索请求 =====");
        SearchResponse response = new SearchResponse();

        try {
            log.info("原始请求参数: {}", request);

            // 参数验证
            if (request.getLocation() == null || request.getRadius() == null) {
                response.setError("10001", "缺少必要参数: location或radius");
                log.error("参数验证失败: location 或 radius 为空");
                return response;
            }

            // 解析经纬度
            String[] coords = request.getLocation().split(",");
            if (coords.length != 2) {
                response.setError("10002", "location格式不正确，应为: 经度,纬度");
                log.error("location 格式错误: {}", request.getLocation());
                return response;
            }

            double radius;
            float centerLon;
            float centerLat;
            Integer year = request.getYear();
            try {
                centerLon = Float.parseFloat(coords[0]);
                centerLat = Float.parseFloat(coords[1]);
                radius = request.getRadius();
                log.info("解析后的经纬度: lon={}, lat={}", centerLon, centerLat);
            } catch (NumberFormatException e) {
                response.setError("10003", "经纬度格式不正确");
                log.error("经纬度解析失败", e);
                return response;
            }

            log.info("搜索半径: {} 米", radius);
            final double queryLon = centerLon;
            final double queryLat = centerLat;

//            double[] wgs84Center = LocationUtils.gcj02ToWgs84(centerLon, centerLat);
//            final float queryLon = (float) wgs84Center[0];
//            final float queryLat = (float) wgs84Center[1];
//            log.info("GCJ-02转WGS84后经纬度: lon={}, lat={}", queryLon, queryLat);

            // 处理类型参数
            List<String> typeCodesParam = null;
            if (StringUtils.hasText(request.getTypes())) {
                typeCodesParam = Arrays.asList(request.getTypes().split("/"));
            }
            boolean typeCodesEmpty = (typeCodesParam == null || typeCodesParam.isEmpty());
            Integer pageSizeParam = request.getPageSize();
            int pageSize = pageSizeParam == null || pageSizeParam <= 0 ? 25 : pageSizeParam;
            int pageNum = request.getPageNum() == null || request.getPageNum() <= 0 ? 1 : request.getPageNum();
            Pageable pageable = pageSizeParam != null && pageSizeParam == -1
                    ? Pageable.unpaged()
                    : PageRequest.of(pageNum - 1, pageSize);

            // 执行查询
            log.info("开始执行数据库查询...");
            List<Region> results = regionRepository.findAround(
                    queryLon,
                    queryLat,
                    radius,
                    year,
                    typeCodesParam,
                    typeCodesEmpty,
                    pageable
            );
            log.info("数据库查询完成，返回 {} 条结果", results.size());

            if (results.isEmpty()) {
                log.warn("未找到匹配的地点");
                response.setPois(Collections.emptyList());
                return response;
            }

            // 转换结果
            List<PoiResponse> pois = results.stream().map(region -> {
                try {
                    // 构建响应对象
                    PoiResponse poi = new PoiResponse();
                    poi.setId(region.getId());
                    poi.setName(region.getName());

                    // 处理typecode，补零到6位
                    String typecode = region.getTypecode();
                    if (typecode != null && typecode.length() < 6) {
                        typecode = String.format("%06d", Integer.parseInt(typecode));
                    }
                    poi.setTypecode(typecode);

                    poi.setType(String.join(";",
                            region.getCategoryLarge(),
                            region.getCategoryMedium(),
                            region.getCategorySmall()));
                    poi.setAddress(region.getAddress());
                    poi.setPname(region.getPname());
                    poi.setCityname(region.getCityname());
                    poi.setAdname(region.getAdname());
                    poi.setAdcode(region.getAdcode());
                    poi.setLocation(region.getMarlon() + "," + region.getMarlat());

                    // 计算实际距离（欧几里得距离）
                    Double poiLon = region.getMarlon();
                    Double poiLat = region.getMarlat();
                    double distance = LocationUtils.haversineMeters(queryLon, queryLat, poiLon, poiLat);
                    // 转换为字符串，距离取整（米）
                    poi.setDistance(String.valueOf(Math.round(distance)));

                    if (region.getTimestamp() != null) {
                        // 直接使用LocalDateTime的getYear()方法
                        poi.setYear(String.valueOf(region.getTimestamp().getYear()));
                    }

                    return poi;
                } catch (Exception e) {
                    log.error("结果转换失败", e);
                    throw new RuntimeException("结果转换异常", e);
                }
            }).collect(Collectors.toList());

            log.info("结果转换完成，共生成 {} 个 POI", pois.size());
            response.setPois(pois);

        } catch (Exception e) {
            log.error("服务器发生未知错误", e);
            response.setError("10004", "服务器错误: " + e.getMessage());
        }

        log.info("===== 请求处理完成 =====");
        return response;
    }

    public SearchResponse searchByCity(CitySearchRequest request) {
        log.info("===== 开始处理城市搜索请求 =====");
        SearchResponse response = new SearchResponse();

        try {
            log.info("原始请求参数: {}", request);

            // 参数验证
            if (!StringUtils.hasText(request.getCityName()) && !StringUtils.hasText(request.getCityCode())) {
                response.setError("20001", "缺少必要参数: cityName或cityCode");
                log.error("参数验证失败: cityName 和 cityCode 为空");
                return response;
            }

            Integer year = request.getYear();
            List<String> typeCodesParam = null;

            if (StringUtils.hasText(request.getTypes())) {
                typeCodesParam = Arrays.asList(request.getTypes().split("/"));
            }
            Integer pageSizeParam = request.getPageSize();
            int pageSize = pageSizeParam == null || pageSizeParam <= 0 ? 25 : pageSizeParam;
            int pageNum = request.getPageNum() == null || request.getPageNum() <= 0 ? 1 : request.getPageNum();
            Pageable pageable = pageSizeParam != null && pageSizeParam == -1
                    ? Pageable.unpaged()
                    : PageRequest.of(pageNum - 1, pageSize);

            String cityname = request.getCityName();
            if (StringUtils.hasText(cityname)) {
                log.info("跳过查询code，搜索条件: cityname={}, year={}, types={}", cityname, year, typeCodesParam);
            } else {
                String citycode = request.getCityCode();
                log.info("搜索条件: citycode={}, year={}, types={}", citycode, year, typeCodesParam);

                // 验证citycode是否存在
                Optional<Code> codeOpt = codeRepository.findFirstByCitycode(citycode);
                if (codeOpt.isEmpty()) {
                    response.setError("20002", "无效的城市编码: " + citycode);
                    log.error("无效的城市编码: {}", citycode);
                    return response;
                }

                cityname = codeOpt.get().getCityname();
                log.info("找到城市: citycode={}, cityname={}", citycode, cityname);
            }

            // 执行查询
            List<Region> results = regionRepository.findByCityAndFilters(
                    cityname,
                    year,
                    typeCodesParam,
                    pageable
            );

            log.info("数据库查询完成，返回 {} 条结果", results.size());

            if (results.isEmpty()) {
                log.warn("未找到匹配的地点");
                response.setPois(Collections.emptyList());
                return response;
            }

            // 转换结果（与searchAround方法保持一致）
            List<PoiResponse> pois = results.stream().map(region -> {
                try {
                    // 构建响应对象
                    PoiResponse poi = new PoiResponse();
                    poi.setId(region.getId());
                    poi.setName(region.getName());

                    // 处理typecode，补零到6位
                    String typecode = region.getTypecode();
                    if (typecode != null && typecode.length() < 6) {
                        typecode = String.format("%06d", Integer.parseInt(typecode));
                    }
                    poi.setTypecode(typecode);

                    poi.setType(String.join(";",
                            region.getCategoryLarge(),
                            region.getCategoryMedium(),
                            region.getCategorySmall()));
                    poi.setAddress(region.getAddress());
                    poi.setPname(region.getPname());
                    poi.setCityname(region.getCityname());
                    poi.setAdname(region.getAdname());
                    poi.setAdcode(region.getAdcode());
                    poi.setLocation(region.getMarlon() + "," + region.getMarlat());
                    poi.setDistance("");  // 城市查询不计算距离

                    if (region.getTimestamp() != null) {
                        // 直接使用LocalDateTime的getYear()方法
                        poi.setYear(String.valueOf(region.getTimestamp().getYear()));
                    }

                    return poi;
                } catch (Exception e) {
                    log.error("结果转换失败", e);
                    throw new RuntimeException("结果转换异常", e);
                }
            }).collect(Collectors.toList());

            log.info("结果转换完成，共生成 {} 个 POI", pois.size());
            response.setPois(pois);

        } catch (Exception e) {
            log.error("服务器发生未知错误", e);
            response.setError("20003", "服务器错误: " + e.getMessage());
        }

        log.info("===== 请求处理完成 =====");
        return response;
    }
}
