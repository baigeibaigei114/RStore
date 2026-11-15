package com.remotesensing.platform.mapper;

import com.remotesensing.platform.vo.MonitoringRegionVO;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonitoringRegionMapper {
    String COLUMNS = "id, name, ST_AsGeoJSON(geom, 15) AS geometry_json, version";

    @Select("SELECT " + COLUMNS + " FROM monitoring_region WHERE owner_id=#{owner} ORDER BY id DESC LIMIT 100")
    List<MonitoringRegionVO> list(@Param("owner") String owner);

    @Select("""
        SELECT id, name, version FROM monitoring_region
        WHERE owner_id=#{owner} AND (#{keyword,jdbcType=VARCHAR} IS NULL OR strpos(name, #{keyword}) > 0)
        ORDER BY id DESC LIMIT 11
        """)
    List<MonitoringRegionVO> searchForPreparation(@Param("owner") String owner, @Param("keyword") String keyword);

    @Select("""
        WITH projected AS (
            SELECT ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(#{geometry}), 4326), #{epsg}) AS geom
        ), pixels AS (
            SELECT ST_SetSRID(ST_Affine(geom, #{a}, #{b}, #{d}, #{e}, #{xoff}, #{yoff}), 0) AS geom FROM projected
        )
        SELECT CASE
            WHEN ST_Covers(ST_MakeEnvelope(-1e-7, -1e-7, #{width} + 1e-7, #{height} + 1e-7), geom) THEN 'FULL'
            WHEN ST_Intersects(ST_MakeEnvelope(0, 0, #{width}, #{height}), geom) THEN 'PARTIAL'
            ELSE 'OUTSIDE'
        END FROM pixels
        """)
    String rasterCoverage(@Param("geometry") String geometry, @Param("epsg") int epsg,
                          @Param("width") int width, @Param("height") int height,
                          @Param("a") double a, @Param("b") double b, @Param("d") double d, @Param("e") double e,
                          @Param("xoff") double xoff, @Param("yoff") double yoff);

    @Select("SELECT " + COLUMNS + " FROM monitoring_region WHERE id=#{id} AND owner_id=#{owner}")
    MonitoringRegionVO find(@Param("id") Long id, @Param("owner") String owner);

    @Select("SELECT " + COLUMNS + " FROM monitoring_region WHERE id=#{id} AND owner_id=#{owner} FOR UPDATE")
    MonitoringRegionVO lock(@Param("id") Long id, @Param("owner") String owner);

    @Select("""
        SELECT ST_IsValid(g) AND NOT ST_IsEmpty(g) AND ST_Area(g)>0
        FROM (SELECT ST_SetSRID(ST_GeomFromGeoJSON(#{geometry}),4326) AS g) q
        """)
    boolean valid(@Param("geometry") String geometry);

    @Insert("""
        INSERT INTO monitoring_region(owner_id,name,geom)
        VALUES(#{owner},#{region.name},ST_SetSRID(ST_GeomFromGeoJSON(#{region.geometryJson}),4326))
        """)
    @Options(useGeneratedKeys = true, keyProperty = "region.id")
    int insert(@Param("owner") String owner, @Param("region") MonitoringRegionVO region);

    @Update("""
        UPDATE monitoring_region
        SET name=#{region.name},geom=ST_SetSRID(ST_GeomFromGeoJSON(#{region.geometryJson}),4326),
            version=version+1,updated_at=CURRENT_TIMESTAMP
        WHERE id=#{region.id} AND owner_id=#{owner}
        """)
    int update(@Param("owner") String owner, @Param("region") MonitoringRegionVO region);
}
