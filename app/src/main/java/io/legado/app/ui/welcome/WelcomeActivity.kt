package io.legado.app.ui.welcome


import android.os.Bundle
import androidx.activity.addCallback
import androidx.core.content.edit
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)

    private val pages = listOf(
        PrivacyFragment(),
        WebDavFragment(),
        BookFolderFragment(),
        ThemeFragment()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) {
            val current = binding.viewPager.currentItem
            if (current > 0) {
                binding.viewPager.currentItem = current - 1
            } else {
                finish()
            }
        }

        updateProgress(0)

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = pages.size
            override fun createFragment(position: Int) = pages[position]
        }

        binding.viewPager.isUserInputEnabled = false

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.btnNext.text = when (position) {
                    0 -> "阅读并同意" // PrivacyFragment
                    pages.lastIndex -> "完成"
                    else -> "下一步"
                }
                binding.tvTitle.text = when (position) {
                    0 -> "欢迎！" // PrivacyFragment
                    1 -> "备份与恢复"
                    2 -> "书籍文件夹"
                    else -> "主题样式"
                }
                binding.tvSummary.text = when (position) {
                    0 -> "请先阅读应用的服务条款与用户协议。"
                    1 -> "此处可设置云同步与恢复应用备份。"
                    2 -> "请选择保存本地书籍的文件夹。"
                    else -> "在这里设置您喜爱的样式。"
                }
                updateProgress(position)
            }
        })

        // 初始化按钮文字
        binding.btnNext.text = "阅读并同意"

        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            when (current) {
                0 -> {
                    LocalConfig.privacyPolicyOk = true
                    binding.viewPager.currentItem = 1
                }

                pages.lastIndex -> {
                    updateProgress(2)
                    finishSetup()
                }

                else -> {
                    binding.viewPager.currentItem = current + 1
                }
            }
        }
    }

    private fun updateProgress(position: Int) {
        val progressMax = pages.size
        val progress = (position * 100 / progressMax)
        binding.progressBar.setProgress(progress, true)
    }

    private fun finishSetup() {
        requestStoragePermission()
    }

    private fun requestStoragePermission() {
        // 检查是否已经在初始化时申请过权限，如果是则不再申请
        val prefs = getSharedPreferences("permission_init", MODE_PRIVATE)
        val hasRequested = prefs.getBoolean("storage_permission_requested", false)
        if (hasRequested) {
            startMainActivity()
            return
        }
        // 标记已申请过
        prefs.edit { putBoolean("storage_permission_requested", true) }

        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                startMainActivity()
            }
            .onDenied {
                // 权限被拒绝仍继续启动
                startMainActivity()
            }
            .onError {
                // 出错仍继续启动
                startMainActivity()
            }
            .request()
    }

    private fun startMainActivity() {
        startActivity(MainActivity.createHomeIntent(this))
        finish()
    }
}
