# Screen Understanding Vendor Product Configuration

QAC_SU := screenunderstanding
QAC_SU += QaiorScreenUnderstandingService
QAC_SU += privapp-permissions-screen_understanding.xml

# Add Screen Understanding to product packages
ifneq ($(TARGET_IS_QLMD), true)
PRODUCT_PACKAGES += $(QAC_SU)
endif #TARGET_IS_QLMD
