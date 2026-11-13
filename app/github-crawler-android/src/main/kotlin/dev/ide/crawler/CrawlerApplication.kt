package dev.ide.crawler

import android.app.Application

/**
 * The crawler application.
 *
 * There is nothing to initialise eagerly: the index is opened by the
 * foreground service, and the token is only read when a crawl actually runs.
 */
class CrawlerApplication : Application()
