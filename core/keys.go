package core

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"encoding/pem"
	"fmt"
	"strings"

	"golang.org/x/crypto/ssh"
)

// KeyPair 是產生的金鑰對:PrivateKey 為 OpenSSH PEM,PublicKey 為 authorized_keys 格式。
type KeyPair struct {
	PrivateKey  string
	PublicKey   string
	Fingerprint string
}

// GenerateKey 產生新金鑰;kind = "ed25519" | "ecdsa" | "rsa"。passphrase 可空。
func GenerateKey(kind, comment, passphrase string) (*KeyPair, error) {
	var priv any
	var err error
	switch kind {
	case "", "ed25519":
		_, priv, err = ed25519.GenerateKey(rand.Reader)
	case "ecdsa":
		priv, err = ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	case "rsa":
		priv, err = rsa.GenerateKey(rand.Reader, 3072)
	default:
		return nil, fmt.Errorf("unsupported key type %q", kind)
	}
	if err != nil {
		return nil, err
	}
	var block *pem.Block
	if passphrase != "" {
		block, err = ssh.MarshalPrivateKeyWithPassphrase(priv, comment, []byte(passphrase))
	} else {
		block, err = ssh.MarshalPrivateKey(priv, comment)
	}
	if err != nil {
		return nil, err
	}
	signer, err := ssh.NewSignerFromKey(priv)
	if err != nil {
		return nil, err
	}
	return &KeyPair{
		PrivateKey:  string(pem.EncodeToMemory(block)),
		PublicKey:   authorizedKey(signer.PublicKey(), comment),
		Fingerprint: ssh.FingerprintSHA256(signer.PublicKey()),
	}, nil
}

// InspectKey 解析私鑰並回傳公鑰資訊(PrivateKey 欄位留空)。
func InspectKey(privateKey, passphrase string) (*KeyPair, error) {
	signer, err := parseSigner(privateKey, passphrase)
	if err != nil {
		return nil, err
	}
	return &KeyPair{
		PublicKey:   authorizedKey(signer.PublicKey(), ""),
		Fingerprint: ssh.FingerprintSHA256(signer.PublicKey()),
	}, nil
}

// KeyNeedsPassphrase 回報私鑰是否加密。
func KeyNeedsPassphrase(privateKey string) bool {
	_, err := ssh.ParsePrivateKey([]byte(privateKey))
	_, missing := err.(*ssh.PassphraseMissingError)
	return missing
}

func authorizedKey(k ssh.PublicKey, comment string) string {
	s := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(k)))
	if comment != "" {
		s += " " + comment
	}
	return s
}
