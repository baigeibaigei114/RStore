package com.remotesensing.platform.mapper;

import com.remotesensing.platform.vo.MonitoringRegionVO;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonitoringRegionMapper {
    String COLUMNS = "id, name, ST_AsGeoJSON(geom, 15) AS geometry_json, version";

    @Select("SELECT " + COLUMNS + " FROM monitoring_region WHERE owner_id=#{owner} ORDER BY id DESC LIMIT 100")
    List<MonitoringRegionVO> list(@Param("owner") String owner);

    @Select("SELECT " + COLUMNS + " FROM monitoring_region WHERE id=#{id} AND owner_id=#{owner}")
    MonitoringRegionVO find(@Param("id") Long id, @Param("owner") String owner);

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
