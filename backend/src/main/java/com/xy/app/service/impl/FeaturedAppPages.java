package com.xy.app.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.app.model.vo.AppFeaturedSlice;
import com.xy.app.model.vo.AppFeaturedVO;
import com.xy.config.DeploymentConfiguration;
import com.xy.config.FeaturedCacheConfiguration;
import com.xy.manager.GeneratedFileManager;
import java.util.UUID;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
public class FeaturedAppPages {

    static final int CACHED_PAGES = 10;
    private final AppMapper apps;
    private final GeneratedFileManager files;

    public FeaturedAppPages(AppMapper apps, GeneratedFileManager files) {
        this.apps = apps;
        this.files = files;
    }

    @Cacheable(cacheNames = FeaturedCacheConfiguration.REVISION, key = "'current'")
    public String currentRevision() {
        return UUID.randomUUID().toString();
    }

    // 写入成功后换代。正在查库的旧请求只能回填旧键，不会盖住其他实例读到的新页。
    @CachePut(cacheNames = FeaturedCacheConfiguration.REVISION, key = "'current'")
    public String changeRevision() {
        return UUID.randomUUID().toString();
    }

    @Cacheable(cacheNames = FeaturedCacheConfiguration.PAGES, condition = "#revision != null",
            key = "#site.publicOrigin() + ':' + #revision + ':' + #current + ':' + #pageSize")
    public AppFeaturedSlice read(int current, int pageSize, String revision, DeploymentConfiguration site) {
        var query = QueryWrapper.create()
                .eq(App::getFeatured, true).isNotNull(App::getPublishedVersion).isNotNull(App::getDeployKey)
                .orderBy(App::getDeployedAt, false).orderBy(App::getId, false)
                .limit((current - 1L) * pageSize, pageSize + 1);
        var matches = apps.selectListByQuery(query);
        var cards = matches.stream().limit(pageSize)
                .map(app -> new AppFeaturedVO(Long.toString(app.getId()), app.getAppName(),
                        app.getCodeGenType(), site.siteUrl(app.getDeployKey()),
                        files.hasCover(app.getUserId(), app.getId(), app.getPublishedVersion())
                                ? "/api/apps/" + app.getId() + "/covers/" + app.getPublishedVersion() : null)).toList();
        return new AppFeaturedSlice(cards, matches.size() > pageSize);
    }
}
