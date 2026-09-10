"""
Qoder Creator - Automated account creation.
No Google OAuth, no Qoder Desktop needed.
Flow: tempik (temp mail) → signup → captcha → OTP → PAT created.
"""
from .config import *
from .utils import *
from .tempmail import TempikClient
from .proxy import ProxyPool
from .captcha import solve_slider_local
from .signup import SignupManager, create_accounts
from .pat import PATManager