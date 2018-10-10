CREATE TABLE SNAPSHOTS
(
    UUID CHARACTER(36) NOT NULL,
    NODE_NAME VARCHAR(255) NOT NULL,
    RESOURCE_NAME VARCHAR(48) NOT NULL,
    SNAPSHOT_NAME VARCHAR(48) NOT NULL,
    SNAPSHOT_FLAGS BIGINT NOT NULL,
    CONSTRAINT PK_S PRIMARY KEY (NODE_NAME, RESOURCE_NAME, SNAPSHOT_NAME),
    CONSTRAINT FK_S_NODES FOREIGN KEY (NODE_NAME) REFERENCES NODES(NODE_NAME),
    CONSTRAINT FK_S_SNAPSHOT_DFN FOREIGN KEY (RESOURCE_NAME, SNAPSHOT_NAME)
        REFERENCES SNAPSHOT_DEFINITIONS(RESOURCE_NAME, SNAPSHOT_NAME),
    CONSTRAINT UNQ_S_UUID UNIQUE (UUID)
);

CREATE TABLE SNAPSHOT_VOLUME_DEFINITIONS
(
    UUID CHARACTER(36) NOT NULL,
    RESOURCE_NAME VARCHAR(48) NOT NULL,
    SNAPSHOT_NAME VARCHAR(48) NOT NULL,
    VLM_NR INT NOT NULL,
    CONSTRAINT PK_SVD PRIMARY KEY (RESOURCE_NAME, SNAPSHOT_NAME, VLM_NR),
    CONSTRAINT FK_SVD_SNAPSHOT_DFN FOREIGN KEY (RESOURCE_NAME, SNAPSHOT_NAME)
        REFERENCES SNAPSHOT_DEFINITIONS(RESOURCE_NAME, SNAPSHOT_NAME),
    CONSTRAINT UNQ_SVD_UUID UNIQUE (UUID)
);

CREATE TABLE SNAPSHOT_VOLUMES
(
    UUID CHARACTER(36) NOT NULL,
    NODE_NAME VARCHAR(255) NOT NULL,
    RESOURCE_NAME VARCHAR(48) NOT NULL,
    SNAPSHOT_NAME VARCHAR(48) NOT NULL,
    VLM_NR INT NOT NULL,
    STOR_POOL_NAME VARCHAR(32) NOT NULL,
    CONSTRAINT PK_SV PRIMARY KEY (NODE_NAME, RESOURCE_NAME, SNAPSHOT_NAME, VLM_NR),
    CONSTRAINT FK_SV_SNAPSHOTS FOREIGN KEY (NODE_NAME, RESOURCE_NAME, SNAPSHOT_NAME)
        REFERENCES SNAPSHOTS(NODE_NAME, RESOURCE_NAME, SNAPSHOT_NAME),
    CONSTRAINT FK_SV_SNAPSHOT_VLM_DFNS FOREIGN KEY (RESOURCE_NAME, SNAPSHOT_NAME, VLM_NR)
        REFERENCES SNAPSHOT_VOLUME_DEFINITIONS(RESOURCE_NAME, SNAPSHOT_NAME, VLM_NR),
    CONSTRAINT FK_SV_STOR_POOL_DFNS FOREIGN KEY (STOR_POOL_NAME)
        REFERENCES STOR_POOL_DEFINITIONS(POOL_NAME),
    CONSTRAINT UNQ_SV_UUID UNIQUE (UUID)
);
