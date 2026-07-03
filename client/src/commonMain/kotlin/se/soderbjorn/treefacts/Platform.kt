package se.soderbjorn.treefacts

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform