charset utf-8;
default_type text/html;
autoindex off;

add_header Cache-Control "no-store" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "no-referrer" always;
add_header Content-Security-Policy "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline' data:; style-src 'unsafe-inline' data:; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'" always;

# 只公开完整快照，不提供临时文件、目录列表或平台接口。
location ~ "^/(?<work>[a-f0-9]{32})/(index[.]html)?$" {
    try_files /$work/index.html =404;
}

location / {
    return 404;
}
