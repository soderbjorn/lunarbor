package se.soderbjorn.lunarbor

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform