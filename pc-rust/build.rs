// Windows: exe 에 아이콘·버전 정보 넣기 (탐색기·작업 표시줄에 별 아이콘이 보이게)
fn main() {
    println!("cargo:rerun-if-changed=assets/icon.ico");
    println!("cargo:rerun-if-env-changed=WOORIZIP_VERSION");
    #[cfg(windows)]
    {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("assets/icon.ico");
        res.set("ProductName", "우리집");
        res.set("FileDescription", "우리집 가족 무전기");
        res.set("CompanyName", "우리집");
        if let Ok(v) = std::env::var("WOORIZIP_VERSION") {
            res.set("ProductVersion", &v);
            res.set("FileVersion", &v);
        }
        if let Err(e) = res.compile() {
            println!("cargo:warning=아이콘 넣기 실패: {e}");
        }
    }
}
