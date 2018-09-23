package com.gal.mytasks

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
	var task: ThreadPool.UITask<Unit>? = null
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		progressBar.setOnClickListener { start() }
	}

	private fun start() {
		task?.cancel()

		task = object : ThreadPool.ProgressedTask<Int, Unit>() {
			override fun task() {
				var prog = 0
				while (prog <= 100) {
					if (isCanceled) return
					updateProgress(prog++)
					Thread.sleep(100)
				}
			}

			override fun onProgress(progress: Int) {
				progressBar.progress = progress
			}
		}

		task?.execute()
	}
}
