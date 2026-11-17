package io.github.forget_the_bright.tsdb.ge.nativeapi;

/**
 * GE Historian REST API 全量端点目录。
 *
 * <p>本枚举逐一列出 GE Historian 原 SDK（GE_REST_SDK 1.0.6.6）中
 * Data、Tags、Systems、Collectors、Alarms 五个模块的全部 REST 端点，
 * 共 92 个。枚举项保留了原 SDK 的中文说明、路径、HTTP 方法与参数位置，
 * 是 {@link io.github.forget_the_bright.tsdb.ge.internal.GeNativeApi#invoke}
 * 的元数据来源。</p>
 *
 * <p><strong>为什么需要这个枚举：</strong>公共层只抽取跨厂商通用的查询能力，
 * 但 GE 的标签管理、采集器运维、系统管理、告警等能力不应被阉割。
 * 业务代码可以通过本枚举直接调用任意原生端点，从而获得与原 SDK
 * 等价的完整能力；公共层与原生层互不替代。</p>
 *
 * <p><strong>参数位置说明：</strong>{@link Position#PATH} 表示路径占位符
 * （例如 <code>{tagName}</code>），{@link Position#QUERY} 表示 URL 查询参数，
 * {@link Position#BODY} 表示 JSON 请求体，{@link Position#NONE} 表示无参数。
 * 具体参数名可从路径模板或 GE REST 文档获得。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum GeEndpoint {

    /** 创建告警备份。路径 <code>/v1/alarms/backup</code>，HTTP 方法 POST。 */
    CREATE_BACKUP("alarms", "创建告警备份", "/v1/alarms/backup", "POST", Position.BODY, Position.NONE),

    /** 创建告警。路径 <code>/v1/alarms/create</code>，HTTP 方法 POST。 */
    CREATE_ALARM("alarms", "创建告警", "/v1/alarms/create", "POST", Position.BODY, Position.NONE),

    /** 清除时间段内的告警。路径 <code>/v1/alarms/delete</code>，HTTP 方法 POST。 */
    DELETE_ALARMS("alarms", "清除时间段内的告警", "/v1/alarms/delete", "POST", Position.BODY, Position.NONE),

    /** 查询告警和事件数据。路径 <code>/v1/alarms/query</code>，HTTP 方法 POST。 */
    QUERY_ALARMS("alarms", "查询告警和事件数据", "/v1/alarms/query", "POST", Position.BODY, Position.NONE),

    /** 恢复告警备份。路径 <code>/v1/alarms/restore</code>，HTTP 方法 POST。 */
    RESTORE_BACKUP("alarms", "恢复告警备份", "/v1/alarms/restore", "POST", Position.BODY, Position.NONE),

    /** 设置 Azure 调试信息日志级别。路径 <code>/v1/collector/azureloglevel</code>，HTTP 方法 PUT。 */
    SET_AZURE_LOG_LEVEL("collectors", "设置 Azure 调试信息日志级别", "/v1/collector/azureloglevel", "PUT", Position.BODY, Position.NONE),

    /** 删除或移动缓冲区文件。路径 <code>/v1/collector/buffercontrol</code>，HTTP 方法 PUT。 */
    BUFFER_CONTROL("collectors", "删除或移动缓冲区文件", "/v1/collector/buffercontrol", "PUT", Position.BODY, Position.NONE),

    /** 创建收集器实例。路径 <code>/v1/collector/createnewinstance</code>，HTTP 方法 POST。 */
    CREATE_NEW_INSTANCE("collectors", "创建收集器实例", "/v1/collector/createnewinstance", "POST", Position.BODY, Position.NONE),

    /** 删除收集器实例。路径 <code>/v1/collector/deleteinstance</code>，HTTP 方法 PUT。 */
    DELETE_COLLECTOR_INSTANCE("collectors", "删除收集器实例", "/v1/collector/deleteinstance", "PUT", Position.BODY, Position.NONE),

    /** 删除离线收集器实例。路径 <code>/v1/collector/deleteofflineinstance</code>，HTTP 方法 DELETE。 */
    DELETE_OFFLINE_INSTANCE("collectors", "删除离线收集器实例", "/v1/collector/deleteofflineinstance", "DELETE", Position.BODY, Position.NONE),

    /** 查看收集器详细信息。路径 <code>/v1/collector/details</code>，HTTP 方法 GET。 */
    COLLECTOR_DETAILS("collectors", "查看收集器详细信息", "/v1/collector/details", "GET", Position.NONE, Position.NONE),

    /** 更新收集器实例。路径 <code>/v1/collector/editinstance</code>，HTTP 方法 PUT。 */
    EDIT_INSTANCE("collectors", "更新收集器实例", "/v1/collector/editinstance", "PUT", Position.BODY, Position.NONE),

    /** 更新收集器的服务器节点。路径 <code>/v1/collector/historiannodechange</code>，HTTP 方法 PUT。 */
    HISTORIAN_NODE_CHANGE("collectors", "更新收集器的服务器节点", "/v1/collector/historiannodechange", "PUT", Position.BODY, Position.NONE),

    /** 获取收集器实例详细信息。路径 <code>/v1/collector/instancedetails/{interfaceName}</code>，HTTP 方法 GET。 */
    INSTANCE_DETAILS("collectors", "获取收集器实例详细信息", "/v1/collector/instancedetails/{interfaceName}", "GET", Position.PATH, Position.NONE),

    /** 启用或禁用消息压缩。路径 <code>/v1/collector/messagecompression</code>，HTTP 方法 PUT。 */
    MESSAGE_COMPRESSION("collectors", "启用或禁用消息压缩", "/v1/collector/messagecompression", "PUT", Position.BODY, Position.NONE),

    /** 查看收集器运行模式。路径 <code>/v1/collector/mode/{interfaceName}</code>，HTTP 方法 GET。 */
    COLLECTOR_RUNNING_MODE("collectors", "查看收集器运行模式", "/v1/collector/mode/{interfaceName}", "GET", Position.PATH, Position.NONE),

    /** 暂停收集器数据收集。路径 <code>/v1/collector/pausecollection/{interfaceName}</code>，HTTP 方法 PUT。 */
    PAUSE_COLLECTION("collectors", "暂停收集器数据收集", "/v1/collector/pausecollection/{interfaceName}", "PUT", Position.PATH, Position.NONE),

    /** 重建收集器列表。路径 <code>/v1/collector/rebuild/{interfaceName}</code>，HTTP 方法 PUT。 */
    REBUILD_COLLECTORS_LIST("collectors", "重建收集器列表", "/v1/collector/rebuild/{interfaceName}", "PUT", Position.PATH, Position.NONE),

    /** 重新启动收集器。路径 <code>/v1/collector/restart</code>，HTTP 方法 PUT。 */
    RESTART_COLLECTOR("collectors", "重新启动收集器", "/v1/collector/restart", "PUT", Position.BODY, Position.NONE),

    /** 恢复收集器数据收集。路径 <code>/v1/collector/resumecollection/{interfaceName}</code>，HTTP 方法 PUT。 */
    RESUME_COLLECTION("collectors", "恢复收集器数据收集", "/v1/collector/resumecollection/{interfaceName}", "PUT", Position.PATH, Position.NONE),

    /** 设置收集器调试模式。路径 <code>/v1/collector/setdebugmode</code>，HTTP 方法 PUT。 */
    SET_DEBUG_MODE("collectors", "设置收集器调试模式", "/v1/collector/setdebugmode", "PUT", Position.BODY, Position.NONE),

    /** 启动收集器。路径 <code>/v1/collector/start</code>，HTTP 方法 PUT。 */
    START_COLLECTOR("collectors", "启动收集器", "/v1/collector/start", "PUT", Position.BODY, Position.NONE),

    /** 获取收集器状态。路径 <code>/v1/collector/status/{interfaceName}</code>，HTTP 方法 GET。 */
    COLLECTOR_STATUS("collectors", "获取收集器状态", "/v1/collector/status/{interfaceName}", "GET", Position.PATH, Position.NONE),

    /** 停止收集器。路径 <code>/v1/collector/stop</code>，HTTP 方法 PUT。 */
    STOP_COLLECTOR("collectors", "停止收集器", "/v1/collector/stop", "PUT", Position.BODY, Position.NONE),

    /** 获取收集器的版本号。路径 <code>/v1/collector/version</code>，HTTP 方法 GET。 */
    COLLECTOR_VERSION("collectors", "获取收集器的版本号", "/v1/collector/version", "GET", Position.QUERY, Position.NONE),

    /** 获取收集器的版本号（路径参数）。路径 <code>/v1/collector/version/{interfaceName}</code>，HTTP 方法 GET。 */
    COLLECTOR_VERSION_BY_PATH("collectors", "获取收集器的版本号（路径参数）", "/v1/collector/version/{interfaceName}", "GET", Position.PATH, Position.NONE),

    /** 获取收集器关联的代理机器列表。路径 <code>/v1/collectormanagerlist</code>，HTTP 方法 GET。 */
    COLLECTOR_MANAGER_LIST("collectors", "获取收集器关联的代理机器列表", "/v1/collectormanagerlist", "GET", Position.NONE, Position.NONE),

    /** 获取收集器所在计算机安装组件的详细信息。路径 <code>/v1/installcomponentdetails/{collectorType}/{collectorSubType}/{machine}</code>，HTTP 方法 GET。 */
    INSTALL_COMPONENT_DETAILS("collectors", "获取收集器所在计算机安装组件的详细信息", "/v1/installcomponentdetails/{collectorType}/{collectorSubType}/{machine}", "GET", Position.PATH, Position.NONE),

    /** 获取指定机器上安装的 OPC 警报和事件服务器列表。路径 <code>/v1/localopcaeservers/{machine}</code>，HTTP 方法 GET。 */
    LOCAL_OPC_AE_SERVERS("collectors", "获取指定机器上安装的 OPC 警报和事件服务器列表", "/v1/localopcaeservers/{machine}", "GET", Position.PATH, Position.NONE),

    /** 查看指定机器上安装的 OPC HDA 服务器列表。路径 <code>/v1/localopchdaservers/{machine}</code>，HTTP 方法 GET。 */
    LOCAL_OPC_HDA_SERVERS("collectors", "查看指定机器上安装的 OPC HDA 服务器列表", "/v1/localopchdaservers/{machine}", "GET", Position.PATH, Position.NONE),

    /** 查看指定机器上安装的 OPC 服务器列表。路径 <code>/v1/localopcservers/{machine}</code>，HTTP 方法 GET。 */
    LOCAL_OPC_SERVERS("collectors", "查看指定机器上安装的 OPC 服务器列表", "/v1/localopcservers/{machine}", "GET", Position.PATH, Position.NONE),

    /** 获取离线收集器列表。路径 <code>/v1/offlinecollectors</code>，HTTP 方法 GET。 */
    OFFLINE_COLLECTORS("collectors", "获取离线收集器列表", "/v1/offlinecollectors", "GET", Position.NONE, Position.NONE),

    /** 查询标签列表的计算数据。路径 <code>/v1/datapoints/calculated</code>，HTTP 方法 GET。 */
    GET_CALCULATED_BY_REQUEST_PARAM("data", "查询标签列表的计算数据", "/v1/datapoints/calculated", "GET", Position.QUERY, Position.NONE),

    /** 查询标签列表的计算数据。路径 <code>/v1/datapoints/calculated</code>，HTTP 方法 POST。 */
    GET_CALCULATED_BY_REQUEST_PARAM_POST("data", "查询标签列表的计算数据", "/v1/datapoints/calculated", "POST", Position.QUERY, Position.BODY),

    /** 查询标签列表的计算数据。路径 <code>/v1/datapoints/calculated/{start}/{end}/{calculationMode}/{count}/{intervalMs}</code>，HTTP 方法 POST。 */
    GET_CALCULATED_BY_PATH_VARIABLE_POST("data", "查询标签列表的计算数据", "/v1/datapoints/calculated/{start}/{end}/{calculationMode}/{count}/{intervalMs}", "POST", Position.PATH, Position.BODY),

    /** 查询标签列表的计算数据。路径 <code>/v1/datapoints/calculated/{tagNames}/{start}/{end}/{calculationMode}/{count}/{intervalMs}</code>，HTTP 方法 GET。 */
    GET_CALCULATED_BY_PATH_VARIABLE("data", "查询标签列表的计算数据", "/v1/datapoints/calculated/{tagNames}/{start}/{end}/{calculationMode}/{count}/{intervalMs}", "GET", Position.PATH, Position.NONE),

    /** 配置查询结果。路径 <code>/v1/datapoints/configuration</code>，HTTP 方法 PUT。 */
    CONFIGURE_QUERY_RESULT_BY_REQUEST_PARAM("data", "配置查询结果", "/v1/datapoints/configuration", "PUT", Position.QUERY, Position.NONE),

    /** 配置查询结果。路径 <code>/v1/datapoints/configuration/{maxDataQueryResultSize}</code>，HTTP 方法 PUT。 */
    CONFIGURE_QUERY_RESULT_BY_PATH_VARIABLE("data", "配置查询结果", "/v1/datapoints/configuration/{maxDataQueryResultSize}", "PUT", Position.PATH, Position.NONE),

    /** 创建数据点位标签数据。路径 <code>/v1/datapoints/create</code>，HTTP 方法 POST。 */
    CREATE_TAG_DATA("data", "创建数据点位标签数据", "/v1/datapoints/create", "POST", Position.BODY, Position.NONE),

    /** 查询当前值数据。路径 <code>/v1/datapoints/currentvalue</code>，HTTP 方法 GET。 */
    GET_CURRENT_VALUE_BY_REQUEST_PARAM("data", "查询当前值数据", "/v1/datapoints/currentvalue", "GET", Position.QUERY, Position.NONE),

    /** 查询当前值数据。路径 <code>/v1/datapoints/currentvalue</code>，HTTP 方法 POST。 */
    GET_CURRENT_VALUE_POST("data", "查询当前值数据", "/v1/datapoints/currentvalue", "POST", Position.BODY, Position.NONE),

    /** 查询当前值数据。路径 <code>/v1/datapoints/currentvalue/{tagNames}</code>，HTTP 方法 GET。 */
    GET_CURRENT_VALUE_BY_PATH_VARIABLE("data", "查询当前值数据", "/v1/datapoints/currentvalue/{tagNames}", "GET", Position.PATH, Position.NONE),

    /** 查询标签列表的插值。路径 <code>/v1/datapoints/interpolated</code>，HTTP 方法 GET。 */
    GET_INTERPOLATED_BY_REQUEST_PARAM("data", "查询标签列表的插值", "/v1/datapoints/interpolated", "GET", Position.QUERY, Position.NONE),

    /** 查询标签列表的插值。路径 <code>/v1/datapoints/interpolated</code>，HTTP 方法 POST。 */
    GET_INTERPOLATED_BY_REQUEST_PARAM_POST("data", "查询标签列表的插值", "/v1/datapoints/interpolated", "POST", Position.QUERY, Position.BODY),

    /** 查询标签列表的插值。路径 <code>/v1/datapoints/interpolated/{start}/{end}/{count}/{intervalMs}</code>，HTTP 方法 POST。 */
    GET_INTERPOLATED_BY_PATH_VARIABLE_POST("data", "查询标签列表的插值", "/v1/datapoints/interpolated/{start}/{end}/{count}/{intervalMs}", "POST", Position.PATH, Position.BODY),

    /** 查询标签列表的插值。路径 <code>/v1/datapoints/interpolated/{tagNames}/{start}/{end}/{count}/{intervalMs}</code>，HTTP 方法 GET。 */
    GET_INTERPOLATED_BY_PATH_VARIABLE("data", "查询标签列表的插值", "/v1/datapoints/interpolated/{tagNames}/{start}/{end}/{count}/{intervalMs}", "GET", Position.PATH, Position.NONE),

    /** 查询原始数据。路径 <code>/v1/datapoints/raw</code>，HTTP 方法 GET。 */
    GET_RAW_DATA_BY_REQUEST_PARAM("data", "查询原始数据", "/v1/datapoints/raw", "GET", Position.QUERY, Position.NONE),

    /** 查询原始数据。路径 <code>/v1/datapoints/raw</code>，HTTP 方法 POST。 */
    GET_RAW_DATA_BY_REQUEST_PARAM_POST("data", "查询原始数据", "/v1/datapoints/raw", "POST", Position.QUERY, Position.BODY),

    /** 查询原始数据。路径 <code>/v1/datapoints/raw/{start}/{end}/{direction}/{count}</code>，HTTP 方法 POST。 */
    GET_RAW_DATA_BY_PATH_VARIABLE_POST("data", "查询原始数据", "/v1/datapoints/raw/{start}/{end}/{direction}/{count}", "POST", Position.PATH, Position.BODY),

    /** 查询原始数据。路径 <code>/v1/datapoints/raw/{tagNames}/{start}/{end}/{direction}/{count}</code>，HTTP 方法 GET。 */
    GET_RAW_DATA_BY_PATH_VARIABLE("data", "查询原始数据", "/v1/datapoints/raw/{tagNames}/{start}/{end}/{direction}/{count}", "GET", Position.PATH, Position.NONE),

    /** 查询采样数据。路径 <code>/v1/datapoints/sampled</code>，HTTP 方法 GET。 */
    GET_SAMPLED_BY_REQUEST_PARAM("data", "查询采样数据", "/v1/datapoints/sampled", "GET", Position.QUERY, Position.NONE),

    /** 查询采样数据。路径 <code>/v1/datapoints/sampled</code>，HTTP 方法 POST。 */
    GET_SAMPLED_BY_REQUEST_PARAM_POST("data", "查询采样数据", "/v1/datapoints/sampled", "POST", Position.BODY, Position.NONE),

    /** 查询趋势数据。路径 <code>/v1/datapoints/trend</code>，HTTP 方法 GET。 */
    GET_TREND_DATA_BY_REQUEST_PARAM("data", "查询趋势数据", "/v1/datapoints/trend", "GET", Position.QUERY, Position.NONE),

    /** 查询趋势数据。路径 <code>/v1/datapoints/trend</code>，HTTP 方法 POST。 */
    GET_TREND_DATA_BY_REQUEST_PARAM_POST("data", "查询趋势数据", "/v1/datapoints/trend", "POST", Position.BODY, Position.NONE),

    /** 更新数据存储。路径 <code>/v1/dataStore/{dataStoreName}</code>，HTTP 方法 PUT。 */
    UPDATE_DATA_STORE("systems", "更新数据存储", "/v1/dataStore/{dataStoreName}", "PUT", Position.PATH, Position.BODY),

    /** 删除数据存储。路径 <code>/v1/datastore</code>，HTTP 方法 DELETE。 */
    DELETE_DATA_STORE("systems", "删除数据存储", "/v1/datastore", "DELETE", Position.BODY, Position.NONE),

    /** 获取数据存储列表。路径 <code>/v1/datastores</code>，HTTP 方法 GET。 */
    GET_DATA_STORES("systems", "获取数据存储列表", "/v1/datastores", "GET", Position.QUERY, Position.NONE),

    /** 添加数据存储。路径 <code>/v1/datastoretostorage</code>，HTTP 方法 POST。 */
    ADD_DATA_STORE("systems", "添加数据存储", "/v1/datastoretostorage", "POST", Position.BODY, Position.NONE),

    /** 获取DHS机器列表。路径 <code>/v1/dhsmachines</code>，HTTP 方法 GET。 */
    GET_DHS_MACHINES("systems", "获取DHS机器列表", "/v1/dhsmachines", "GET", Position.QUERY, Position.NONE),

    /** 更新DHS服务信息。路径 <code>/v1/dhsservice/{dHSServiceName}</code>，HTTP 方法 PUT。 */
    UPDATE_DHS_SERVICE("systems", "更新DHS服务信息", "/v1/dhsservice/{dHSServiceName}", "PUT", Position.PATH, Position.BODY),

    /** 获取DHS服务列表。路径 <code>/v1/dhsservices</code>，HTTP 方法 GET。 */
    GET_DHS_SERVICES("systems", "获取DHS服务列表", "/v1/dhsservices", "GET", Position.QUERY, Position.NONE),

    /** 获取指定用户下的服务器信息。路径 <code>/v1/getservers/{loginuser}</code>，HTTP 方法 GET。 */
    GET_SERVERS("systems", "获取指定用户下的服务器信息", "/v1/getservers/{loginuser}", "GET", Position.PATH, Position.NONE),

    /** 添加服务器并归当前用户管理。路径 <code>/v1/historianserver/addServer</code>，HTTP 方法 POST。 */
    ADD_HISTORIAN_SERVER("systems", "添加服务器并归当前用户管理", "/v1/historianserver/addServer", "POST", Position.BODY, Position.NONE),

    /** 删除服务器。路径 <code>/v1/historianserver/deleteServer</code>，HTTP 方法 DELETE。 */
    DELETE_HISTORIAN_SERVER("systems", "删除服务器", "/v1/historianserver/deleteServer", "DELETE", Position.BODY, Position.NONE),

    /** 修复服务器。路径 <code>/v1/historianserver/modifyServer</code>，HTTP 方法 PUT。 */
    MODIFY_HISTORIAN_SERVER("systems", "修复服务器", "/v1/historianserver/modifyServer", "PUT", Position.BODY, Position.NONE),

    /** 获取服务器类型信息。路径 <code>/v1/historianserver/type</code>，HTTP 方法 GET。 */
    GET_SERVER_TYPE("systems", "获取服务器类型信息", "/v1/historianserver/type", "GET", Position.NONE, Position.NONE),

    /** 添加机器。路径 <code>/v1/machine</code>，HTTP 方法 POST。 */
    ADD_MACHINE("systems", "添加机器", "/v1/machine", "POST", Position.BODY, Position.NONE),

    /** 删除机器。路径 <code>/v1/machine</code>，HTTP 方法 DELETE。 */
    DELETE_MACHINE("systems", "删除机器", "/v1/machine", "DELETE", Position.BODY, Position.NONE),

    /** 创建镜像组。路径 <code>/v1/mirrorgroup</code>，HTTP 方法 POST。 */
    CREATE_MIRROR_GROUP("systems", "创建镜像组", "/v1/mirrorgroup", "POST", Position.BODY, Position.NONE),

    /** 更新镜像组。路径 <code>/v1/mirrorgroup</code>，HTTP 方法 PUT。 */
    RENAME_MIRROR_GROUP("systems", "更新镜像组", "/v1/mirrorgroup", "PUT", Position.BODY, Position.NONE),

    /** 删除镜像组。路径 <code>/v1/mirrorgroup</code>，HTTP 方法 DELETE。 */
    DELETE_MIRROR_GROUP("systems", "删除镜像组", "/v1/mirrorgroup", "DELETE", Position.BODY, Position.NONE),

    /** 添加镜像机器。路径 <code>/v1/mirrormachine</code>，HTTP 方法 POST。 */
    ADD_MIRROR_MACHINE("systems", "添加镜像机器", "/v1/mirrormachine", "POST", Position.BODY, Position.NONE),

    /** 删除镜像机器。路径 <code>/v1/mirrormachine</code>，HTTP 方法 DELETE。 */
    REMOVE_MIRROR_MACHINE("systems", "删除镜像机器", "/v1/mirrormachine", "DELETE", Position.BODY, Position.NONE),

    /** 获取系统的读取速率和接收速率。路径 <code>/v1/performancecounter/perftagdata/{perfTagName}/{perfTagType}/{name}/{start}/{end}/{intervalMs}</code>，HTTP 方法 GET。 */
    GET_PERF_TAG_DATA("systems", "获取系统的读取速率和接收速率", "/v1/performancecounter/perftagdata/{perfTagName}/{perfTagType}/{name}/{start}/{end}/{intervalMs}", "GET", Position.PATH, Position.NONE),

    /** 获取服务器属性列表。路径 <code>/v1/serverproperties</code>，HTTP 方法 GET。 */
    GET_SERVER_PROPERTIES("systems", "获取服务器属性列表", "/v1/serverproperties", "GET", Position.NONE, Position.NONE),

    /** 获取数据存储列表。路径 <code>/v1/storage/datastores</code>，HTTP 方法 GET。 */
    GET_DATA_STORES_BY_STORAGE("systems", "获取数据存储列表", "/v1/storage/datastores", "GET", Position.QUERY, Position.NONE),

    /** 更新默认数据存储。路径 <code>/v1/storage/{storageName}</code>，HTTP 方法 PUT。 */
    UPDATE_DEFAULT_DATA_STORE("systems", "更新默认数据存储", "/v1/storage/{storageName}", "PUT", Position.PATH, Position.BODY),

    /** 获取存储位置列表。路径 <code>/v1/storages</code>，HTTP 方法 GET。 */
    GET_STORAGES("systems", "获取存储位置列表", "/v1/storages", "GET", Position.QUERY, Position.NONE),

    /** 获取系统统计信息。路径 <code>/v1/systemstats</code>，HTTP 方法 GET。 */
    GET_SYSTEM_STATS("systems", "获取系统统计信息", "/v1/systemstats", "GET", Position.NONE, Position.NONE),

    /** 查询标签列表。路径 <code>/v1/tags</code>，HTTP 方法 GET。 */
    QUERY_TAGS_BY_PARAMS("tags", "查询标签列表", "/v1/tags", "GET", Position.QUERY, Position.NONE),

    /** 添加标签评论。路径 <code>/v1/tags/addcomment</code>，HTTP 方法 POST。 */
    ADD_TAG_COMMENT("tags", "添加标签评论", "/v1/tags/addcomment", "POST", Position.BODY, Position.NONE),

    /** 添加单个标签。路径 <code>/v1/tags/addtag</code>，HTTP 方法 POST。 */
    ADD_SINGLE_TAG("tags", "添加单个标签", "/v1/tags/addtag", "POST", Position.BODY, Position.NONE),

    /** 批量添加标签。路径 <code>/v1/tags/addtags</code>，HTTP 方法 POST。 */
    ADD_BATCH_TAGS("tags", "批量添加标签", "/v1/tags/addtags", "POST", Position.BODY, Position.NONE),

    /** 查询标签评论。路径 <code>/v1/tags/comments</code>，HTTP 方法 GET。 */
    GET_COMMENTS_BY_QUERY("tags", "查询标签评论", "/v1/tags/comments", "GET", Position.QUERY, Position.NONE),

    /** 路径参数查询评论。路径 <code>/v1/tags/comments/{tagNames}/{start}/{end}</code>，HTTP 方法 GET。 */
    GET_COMMENTS_BY_PATH("tags", "路径参数查询评论", "/v1/tags/comments/{tagNames}/{start}/{end}", "GET", Position.PATH, Position.NONE),

    /** GET方式检索属性。路径 <code>/v1/tags/properties/{tagName}</code>，HTTP 方法 GET。 */
    GET_TAG_PROPERTIES_PATH("tags", "GET方式检索属性", "/v1/tags/properties/{tagName}", "GET", Position.PATH, Position.NONE),

    /** POST方式检索属性。路径 <code>/v1/tags/properties/{tagName}</code>，HTTP 方法 POST。 */
    GET_TAG_PROPERTIES_PATH_BODY("tags", "POST方式检索属性", "/v1/tags/properties/{tagName}", "POST", Position.PATH, Position.BODY),

    /** 更新标签属性。路径 <code>/v1/tags/properties/{tagName}</code>，HTTP 方法 PUT。 */
    UPDATE_TAG_PROPERTIES("tags", "更新标签属性", "/v1/tags/properties/{tagName}", "PUT", Position.PATH, Position.BODY),

    /** 标签重命名。路径 <code>/v1/tags/tagrename/{oldTagName}/{newTagName}</code>，HTTP 方法 PUT。 */
    RENAME_TAG("tags", "标签重命名", "/v1/tags/tagrename/{oldTagName}/{newTagName}", "PUT", Position.PATH, Position.QUERY),

    /** 路径参数查询标签。路径 <code>/v1/tags/{nameMask}/{maxNumber}</code>，HTTP 方法 GET。 */
    QUERY_TAGS_BY_PATH("tags", "路径参数查询标签", "/v1/tags/{nameMask}/{maxNumber}", "GET", Position.PATH, Position.NONE),

    /** 删除标签。路径 <code>/v1/tags/{tagName}</code>，HTTP 方法 DELETE。 */
    DELETE_TAG("tags", "删除标签", "/v1/tags/{tagName}", "DELETE", Position.PATH, Position.QUERY),

    /** 高级标签查询。路径 <code>/v1/tagslist</code>，HTTP 方法 GET。 */
    ADVANCED_TAG_QUERY("tags", "高级标签查询", "/v1/tagslist", "GET", Position.QUERY, Position.NONE),
    ;

    /** 端点所属业务模块：data/tags/systems/collectors/alarms。 */
    private final String module;
    /** 原 SDK 中的中文说明。 */
    private final String description;
    /** 相对 /historian-rest-api 的路径模板。 */
    private final String path;
    /** HTTP 方法。 */
    private final String method;
    /** 主参数位置。 */
    private final Position primary;
    /** 次参数位置。 */
    private final Position secondary;

    GeEndpoint(String module, String description, String path, String method,
               Position primary, Position secondary) {
        this.module = module;
        this.description = description;
        this.path = path;
        this.method = method;
        this.primary = primary;
        this.secondary = secondary;
    }

    /** @return 业务模块名。 */
    public String module() { return module; }

    /** @return 中文说明。 */
    public String description() { return description; }

    /** @return 路径模板。 */
    public String path() { return path; }

    /** @return HTTP 方法。 */
    public String method() { return method; }

    /** @return 主参数位置。 */
    public Position primary() { return primary; }

    /** @return 次参数位置。 */
    public Position secondary() { return secondary; }

    /**
     * 参数位置。
     */
    public enum Position {
        /** 无参数。 */
        NONE,
        /** URL 查询参数。 */
        QUERY,
        /** 路径占位符。 */
        PATH,
        /** JSON 请求体。 */
        BODY
    }
}

