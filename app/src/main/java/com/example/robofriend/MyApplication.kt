package com.example.robofriend

import android.app.Application
import android.util.Log
import com.amazonaws.mobile.client.AWSMobileClient
import com.amazonaws.mobile.client.Callback
import com.amazonaws.mobile.client.UserStateDetails

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AWSMobileClient.getInstance().initialize(this, object : Callback<UserStateDetails> {
            override fun onResult(userStateDetails: UserStateDetails) {
                System.setProperty("com.amazonaws.services.s3.enableV4", "true") // Enabling AWS Signature Version 4
                Log.i("INIT", "onResult: " + userStateDetails.userState)
            }

            override fun onError(e: Exception) {
                Log.e("INIT", "Initialization error.", e)
            }
        })
    }
}
