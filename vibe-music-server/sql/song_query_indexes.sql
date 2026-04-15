-- Optimize single-entry song list queries after artistName is resolved to artist_id in service.
CREATE INDEX idx_song_name_release_id ON tb_song (`name`, `release_time` DESC, `id`);
CREATE INDEX idx_song_artist_release_id ON tb_song (`artist_id`, `release_time` DESC, `id`);
