# MyImagerLoader


打造自定义的ImageLoader图片加载类，实现图片加载的三级缓存，其中内存缓存是使用LruCache类实现，而文件缓存是采用DiskLruCache类，网络加载则直接使用HttpUrlConnection来实现，该类还可以选择使用同步或异步的方式加载。


采用该类实现照片墙的效果，优化图片加载时用户快速滑动导致图片加载错位的问题。
